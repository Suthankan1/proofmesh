package com.proofmesh.controlplane.identity.internal.persistence;

import com.proofmesh.controlplane.identity.OrganizationContext;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityMembershipLookup {

    private final JdbcTemplate jdbcTemplate;

    public IdentityMembershipLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OrganizationContext> findActiveContextsBySubject(
            String oidcSubject
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    u.id AS user_id,
                    o.id AS organization_id,
                    o.slug AS organization_slug,
                    u.oidc_subject
                FROM proofmesh.operator_users u
                JOIN proofmesh.organization_memberships m
                  ON m.user_id = u.id
                JOIN proofmesh.organizations o
                  ON o.id = m.organization_id
                WHERE u.oidc_subject = ?
                  AND u.status = 'ACTIVE'
                  AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                ORDER BY o.id
                LIMIT 2
                """,
                (resultSet, rowNum) -> new OrganizationContext(
                        resultSet.getObject("user_id", java.util.UUID.class),
                        resultSet.getObject(
                                "organization_id",
                                java.util.UUID.class
                        ),
                        resultSet.getString("organization_slug"),
                        resultSet.getString("oidc_subject")
                ),
                oidcSubject
        );
    }
}