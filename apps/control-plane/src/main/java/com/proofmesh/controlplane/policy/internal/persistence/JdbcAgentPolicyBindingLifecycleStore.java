package com.proofmesh.controlplane.policy.internal.persistence;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingLifecycleStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
class JdbcAgentPolicyBindingLifecycleStore
        implements AgentPolicyBindingLifecycleStore {

    private final JdbcTemplate jdbcTemplate;

    JdbcAgentPolicyBindingLifecycleStore(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate must not be null"
                );
    }

    @Override
    public boolean lockAgent(
            UUID organizationId,
            UUID agentId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        List<UUID> locked =
                jdbcTemplate.query(
                        """
                        SELECT id
                        FROM proofmesh.agents
                        WHERE organization_id = ?
                          AND id = ?
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) ->
                                resultSet.getObject(
                                        "id",
                                        UUID.class
                                ),
                        organizationId,
                        agentId
                );

        return !locked.isEmpty();
    }

    @Override
    public boolean deactivateOpenBinding(
            UUID organizationId,
            UUID agentId,
            UUID bindingId,
            Instant deactivatedAt
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        Objects.requireNonNull(
                bindingId,
                "bindingId must not be null"
        );

        Objects.requireNonNull(
                deactivatedAt,
                "deactivatedAt must not be null"
        );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.agent_policy_bindings
                        SET deactivated_at = ?
                        WHERE organization_id = ?
                          AND agent_id = ?
                          AND id = ?
                          AND deactivated_at IS NULL
                        """,
                        toUtcOffsetDateTime(
                                deactivatedAt
                        ),
                        organizationId,
                        agentId,
                        bindingId
                );

        if (affectedRows > 1) {
            throw new IllegalStateException(
                    "deactivating one policy binding affected multiple rows"
            );
        }

        return affectedRows == 1;
    }

    @Override
    public void insert(
            AgentPolicyBinding binding
    ) {
        Objects.requireNonNull(
                binding,
                "binding must not be null"
        );

        OffsetDateTime deactivatedAt =
                binding.deactivatedAt() == null
                        ? null
                        : toUtcOffsetDateTime(
                                binding.deactivatedAt()
                        );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        INSERT INTO proofmesh.agent_policy_bindings (
                            id,
                            organization_id,
                            agent_id,
                            policy_version_id,
                            activated_at,
                            deactivated_at,
                            created_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        binding.id(),
                        binding.organizationId(),
                        binding.agentId(),
                        binding.policyVersionId().value(),
                        toUtcOffsetDateTime(
                                binding.activatedAt()
                        ),
                        deactivatedAt,
                        toUtcOffsetDateTime(
                                binding.createdAt()
                        )
                );

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "inserting policy binding did not affect exactly one row"
            );
        }
    }

    private OffsetDateTime toUtcOffsetDateTime(
            Instant instant
    ) {
        return OffsetDateTime.ofInstant(
                instant,
                ZoneOffset.UTC
        );
    }
}