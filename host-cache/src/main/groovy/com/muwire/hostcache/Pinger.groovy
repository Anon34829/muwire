package com.muwire.hostcache

import groovy.json.JsonOutput
import net.i2p.client.I2PSession
import net.i2p.client.datagram.I2PDatagramMaker

class Pinger {
    
    final def session
    Pinger(session) {
        this.session = session
    }
    
    def ping(host, uuid) {
        def maker = new I2PDatagramMaker(session)
        def payload = new HashMap()
        payload.type = "CrawlerPing"
        payload.version = 1
        payload.uuid = uuid
        payload = JsonOutput.toJson(payload)
        payload = maker.makeI2PDatagram(payload.bytes)
        session.sendMessage(host.destination, payload, I2PSession.PROTO_DATAGRAM, 0, 0)
    }
}
