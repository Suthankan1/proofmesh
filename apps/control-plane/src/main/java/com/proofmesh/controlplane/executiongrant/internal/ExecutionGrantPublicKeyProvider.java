package com.proofmesh.controlplane.executiongrant.internal;

import java.security.interfaces.ECPublicKey;

interface ExecutionGrantPublicKeyProvider {

    String activeKeyId();

    ECPublicKey activePublicKey();
}
