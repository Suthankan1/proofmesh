package com.proofmesh.controlplane.policy;

import java.time.Instant;

public interface PolicyPublisher {

    PolicyVersion publish(
            PolicyVersion draft,
            Instant publishedAt
    );
}