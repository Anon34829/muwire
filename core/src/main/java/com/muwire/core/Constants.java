package com.muwire.core;

import net.i2p.crypto.SigType;

public class Constants {
    public static final byte PERSONA_VERSION = (byte)1;
    public static final SigType SIG_TYPE = SigType.EdDSA_SHA512_Ed25519;

    public static final int MAX_HEADER_SIZE = 0x1 << 14;
    public static final int MAX_HEADERS = 16;
}
