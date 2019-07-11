package com.muwire.core.download

import com.muwire.core.InfoHash
import com.muwire.core.Persona
import com.muwire.core.connection.Endpoint

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

import com.muwire.core.Constants
import com.muwire.core.DownloadedFile
import com.muwire.core.EventBus
import com.muwire.core.connection.I2PConnector
import com.muwire.core.files.FileDownloadedEvent
import com.muwire.core.util.DataUtil

import groovy.util.logging.Log
import net.i2p.data.Destination
import net.i2p.util.ConcurrentHashSet

@Log
public class Downloader {
    public enum DownloadState { CONNECTING, HASHLIST, DOWNLOADING, FAILED, CANCELLED, PAUSED, FINISHED }
    private enum WorkerState { CONNECTING, HASHLIST, DOWNLOADING, FINISHED}

    private static final ExecutorService executorService = Executors.newCachedThreadPool({r ->
        Thread rv = new Thread(r)
        rv.setName("download worker")
        rv.setDaemon(true)
        rv
    })

    private final EventBus eventBus
    private final DownloadManager downloadManager
    private final Persona me
    private final File file
    private final Pieces pieces
    private final long length
    private InfoHash infoHash
    private final int pieceSize
    private final I2PConnector connector
    private final Set<Destination> destinations
    private final int nPieces
    private final File piecesFile
    private final File incompleteFile
    final int pieceSizePow2
    private final Map<Destination, DownloadWorker> activeWorkers = new ConcurrentHashMap<>()
    private final Set<Destination> successfulDestinations = new ConcurrentHashSet<>()


    private volatile boolean cancelled, paused
    private final AtomicBoolean eventFired = new AtomicBoolean()
    private boolean piecesFileClosed

    private ArrayList speedArr = new ArrayList<Integer>()
    private int speedPos = 0
    private int speedAvg = 0
    private long timestamp = Instant.now().toEpochMilli()

    public Downloader(EventBus eventBus, DownloadManager downloadManager,
        Persona me, File file, long length, InfoHash infoHash,
        int pieceSizePow2, I2PConnector connector, Set<Destination> destinations,
        File incompletes, Pieces pieces) {
        this.eventBus = eventBus
        this.me = me
        this.downloadManager = downloadManager
        this.file = file
        this.infoHash = infoHash
        this.length = length
        this.connector = connector
        this.destinations = destinations
        this.piecesFile = new File(incompletes, file.getName()+".pieces")
        this.incompleteFile = new File(incompletes, file.getName()+".part")
        this.pieceSizePow2 = pieceSizePow2
        this.pieceSize = 1 << pieceSizePow2
        this.pieces = pieces
        this.nPieces = pieces.nPieces

        // default size suitable for an average of 5 seconds / 5 elements / 5 interval units
        // it's easily adjustable by resizing the size of speedArr
        this.speedArr = [ 0, 0, 0, 0, 0 ]
    }

    public synchronized InfoHash getInfoHash() {
        infoHash
    }

    private synchronized void setInfoHash(InfoHash infoHash) {
        this.infoHash = infoHash
    }

    void download() {
        readPieces()
        destinations.each {
            if (it != me.destination) {
                def worker = new DownloadWorker(it)
                activeWorkers.put(it, worker)
                executorService.submit(worker)
            }
        }
    }

    void readPieces() {
        if (!piecesFile.exists())
            return
        piecesFile.eachLine {
            String [] split = it.split(",")
            int piece = Integer.parseInt(split[0])
            if (split.length == 1)
                pieces.markDownloaded(piece)
            else {
                int position = Integer.parseInt(split[1])
                pieces.markPartial(piece, position)
            }
        }
    }

    void writePieces() {
        synchronized(piecesFile) {
            if (piecesFileClosed)
                return
            piecesFile.withPrintWriter { writer ->
                pieces.write(writer)
            }
        }
    }

    public long donePieces() {
        pieces.donePieces()
    }


    public int speed() {
        int currSpeed = 0
        if (getCurrentState() == DownloadState.DOWNLOADING) {
            activeWorkers.values().each {
                if (it.currentState == WorkerState.DOWNLOADING)
                    currSpeed += it.speed()
            }
        }

        // normalize to speedArr.size
        currSpeed /= speedArr.size()

        // compute new speedAvg and update speedArr
        if ( speedArr[speedPos] > speedAvg ) {
            speedAvg = 0
        } else {
            speedAvg -= speedArr[speedPos]
        }
        speedAvg += currSpeed
        speedArr[speedPos] = currSpeed
        // this might be necessary due to rounding errors
        if (speedAvg < 0)
            speedAvg = 0

        // rolling index over the speedArr
        speedPos++
        if (speedPos >= speedArr.size())
            speedPos=0

        speedAvg
    }

    public DownloadState getCurrentState() {
        if (cancelled)
            return DownloadState.CANCELLED
        if (paused)
            return DownloadState.PAUSED

        boolean allFinished = true
        activeWorkers.values().each {
            allFinished &= it.currentState == WorkerState.FINISHED
        }
        if (allFinished) {
            if (pieces.isComplete())
                return DownloadState.FINISHED
            return DownloadState.FAILED
        }

        // if at least one is downloading...
        boolean oneDownloading = false
        activeWorkers.values().each {
            if (it.currentState == WorkerState.DOWNLOADING) {
                oneDownloading = true
                return
            }
        }

        if (oneDownloading)
            return DownloadState.DOWNLOADING

        // at least one is requesting hashlist
        boolean oneHashlist = false
        activeWorkers.values().each {
            if (it.currentState == WorkerState.HASHLIST) {
                oneHashlist = true
                return
            }
        }
        if (oneHashlist)
            return DownloadState.HASHLIST

        return DownloadState.CONNECTING
    }

    public void cancel() {
        cancelled = true
        stop()
        synchronized(piecesFile) {
            piecesFileClosed = true
            piecesFile.delete()
        }
        incompleteFile.delete()
        pieces.clearAll()
    }

    public void pause() {
        paused = true
        stop()
    }

    void stop() {
        activeWorkers.values().each {
            it.cancel()
        }
    }

    public int activeWorkers() {
        int active = 0
        activeWorkers.values().each {
            if (it.currentState != WorkerState.FINISHED)
                active++
        }
        active
    }

    public void resume() {
        paused = false
        readPieces()
        destinations.each { destination ->
            def worker = activeWorkers.get(destination)
            if (worker != null) {
                if (worker.currentState == WorkerState.FINISHED) {
                    def newWorker = new DownloadWorker(destination)
                    activeWorkers.put(destination, newWorker)
                    executorService.submit(newWorker)
                }
            } else {
                worker = new DownloadWorker(destination)
                activeWorkers.put(destination, worker)
                executorService.submit(worker)
            }
        }
    }

    void addSource(Destination d) {
        if (activeWorkers.containsKey(d))
            return
        DownloadWorker newWorker = new DownloadWorker(d)
        activeWorkers.put(d, newWorker)
        executorService.submit(newWorker)
    }

    class DownloadWorker implements Runnable {
        private final Destination destination
        private volatile WorkerState currentState
        private volatile Thread downloadThread
        private Endpoint endpoint
        private volatile DownloadSession currentSession
        private final Set<Integer> available = new HashSet<>()

        DownloadWorker(Destination destination) {
            this.destination = destination
        }

        public void run() {
            downloadThread = Thread.currentThread()
            currentState = WorkerState.CONNECTING
            Endpoint endpoint = null
            try {
                endpoint = connector.connect(destination)
                while(getInfoHash().hashList == null) {
                    currentState = WorkerState.HASHLIST
                    HashListSession session = new HashListSession(me.toBase64(), infoHash, endpoint)
                    InfoHash received = session.request()
                    setInfoHash(received)
                }
                currentState = WorkerState.DOWNLOADING
                boolean requestPerformed
                while(!pieces.isComplete()) {
                    currentSession = new DownloadSession(eventBus, me.toBase64(), pieces, getInfoHash(),
                        endpoint, incompleteFile, pieceSize, length, available)
                    requestPerformed = currentSession.request()
                    if (!requestPerformed)
                        break
                    successfulDestinations.add(endpoint.destination)
                    writePieces()
                }
            } catch (Exception bad) {
                log.log(Level.WARNING,"Exception while downloading",DataUtil.findRoot(bad))
            } finally {
                writePieces()
                currentState = WorkerState.FINISHED
                if (pieces.isComplete() && eventFired.compareAndSet(false, true)) {
                    synchronized(piecesFile) {
                        piecesFileClosed = true
                        piecesFile.delete()
                    }
                    activeWorkers.values().each { 
                        if (it.destination != destination)
                            it.cancel()
                    }
                    try {
                        Files.move(incompleteFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.copy(incompleteFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        incompleteFile.delete()
                    }
                    eventBus.publish(
                        new FileDownloadedEvent(
                            downloadedFile : new DownloadedFile(file.getCanonicalFile(), getInfoHash(), pieceSizePow2, successfulDestinations),
                        downloader : Downloader.this))

                }
                endpoint?.close()
            }
        }

        int speed() {
            if (currentSession == null)
                return 0
            currentSession.speed()
        }

        void cancel() {
            downloadThread?.interrupt()
        }
    }
}
