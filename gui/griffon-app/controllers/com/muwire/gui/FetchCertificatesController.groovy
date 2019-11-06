package com.muwire.gui

import griffon.core.artifact.GriffonController
import griffon.core.controller.ControllerAction
import griffon.inject.MVCMember
import griffon.metadata.ArtifactProviderFor
import javax.annotation.Nonnull
import javax.swing.JOptionPane

import com.muwire.core.EventBus
import com.muwire.core.filecert.CertificateFetchEvent
import com.muwire.core.filecert.CertificateFetchStatus
import com.muwire.core.filecert.CertificateFetchedEvent
import com.muwire.core.filecert.UIFetchCertificatesEvent
import com.muwire.core.filecert.UIImportCertificateEvent

@ArtifactProviderFor(GriffonController)
class FetchCertificatesController {
    @MVCMember @Nonnull
    FetchCertificatesModel model
    @MVCMember @Nonnull
    FetchCertificatesView view

    EventBus eventBus
    
    void register() {
        eventBus.with { 
            register(CertificateFetchEvent.class, this)
            register(CertificateFetchedEvent.class, this)
            publish(new UIFetchCertificatesEvent(host : model.result.sender, infoHash : model.result.infohash))
        }
    }
    
    void mvcGroupDestroy() {
        eventBus.unregister(CertificateFetchEvent.class, this)
        eventBus.unregister(CertificateFetchedEvent.class, this)
    }
    
    void onCertificateFetchEvent(CertificateFetchEvent e) {
        runInsideUIAsync {
            model.status = e.status
            if (e.status == CertificateFetchStatus.FETCHING)
                model.totalCertificates = e.count
        }
    }
    
    void onCertificateFetchedEvent(CertificateFetchedEvent e) {
        runInsideUIAsync {
            model.certificates << e.certificate
            model.certificateCount = model.certificates.size()
            view.certsTable.model.fireTableDataChanged()
        }
    }
    
    @ControllerAction
    void importCertificates() {
        def selectedCerts = view.selectedCertificates()
        if (selectedCerts == null)
            return
        selectedCerts.each { 
            eventBus.publish(new UIImportCertificateEvent(certificate : it))
        }
        JOptionPane.showMessageDialog(null, "Certificates imported.")
    }
    
    @ControllerAction
    void dismiss() {
        view.dialog.setVisible(false)
        mvcGroup.destroy()
    }
}