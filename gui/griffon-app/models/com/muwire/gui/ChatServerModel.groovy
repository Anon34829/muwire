package com.muwire.gui

import com.muwire.core.Core
import com.muwire.core.Persona
import com.muwire.core.chat.ChatConnectionAttemptStatus
import com.muwire.core.chat.ChatConnectionEvent
import com.muwire.core.chat.ChatLink
import com.muwire.core.chat.ChatMessageEvent
import com.muwire.core.chat.UIConnectChatEvent

import griffon.core.artifact.GriffonModel
import griffon.transform.Observable
import groovy.util.logging.Log
import griffon.metadata.ArtifactProviderFor

@Log
@ArtifactProviderFor(GriffonModel)
class ChatServerModel {
    Persona host
    Core core
    
    @Observable boolean disconnectActionEnabled
    @Observable ChatConnectionAttemptStatus status

    volatile ChatLink link    
    volatile Thread poller
    volatile boolean running
    
    void mvcGroupInit(Map<String, String> params) {
        disconnectActionEnabled = host != core.me // can't disconnect from myself
        
        core.eventBus.with { 
            register(ChatConnectionEvent.class, this)
            publish(new UIConnectChatEvent(host : host))
        }
        
        running = true
        poller = new Thread({eventLoop()} as Runnable)
        poller.setDaemon(true)
        poller.start()
    }
    
    void mvcGroupDestroy() {
        running = false
        poller?.interrupt()
    }
    
    void onChatConnectionEvent(ChatConnectionEvent e) {
        if (e.connection != null)
            link = e.connection
        runInsideUIAsync {
            status = e.status
        }
    }
    
    private void eventLoop() {
        while(running) {
            ChatLink link = this.link
            if (link == null || !link.isUp()) {
                Thread.sleep(100)
                continue
            }
                
            Object event = link.nextEvent()
            if (event instanceof ChatMessageEvent)
                handleChatMessage(event)
            else if (event instanceof Persona)
                handleLeave(event)
            else
                throw new IllegalArgumentException("event type $event")
        }
    }
    
    private void handleChatMessage(ChatMessageEvent e) {
        log.info("dispatching to room ${e.room}")
        mvcGroup.childrenGroups[e.room]?.controller?.handleChatMessage(e)
    }
    
    private void handleLeave(Persona p) {
        mvcGroup.childrenGroups.each { 
            it.controller.handleLeave(p)
        }
    }
}