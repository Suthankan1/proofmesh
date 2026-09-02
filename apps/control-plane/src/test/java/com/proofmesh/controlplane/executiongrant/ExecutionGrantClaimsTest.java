package com.proofmesh.controlplane.executiongrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class ExecutionGrantClaimsTest {

    private static final ExecutionGrantId GRANT_ID =
            new ExecutionGrantId(
                    UUID.fromString(
                            "71000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "72000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "73000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "74000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNANCE_DECISION_ID =
            UUID.fromString(
                    "75000000-0000-0000-0000-000000000001"
            );

    private static final ToolName TOOL_NAME =
            new ToolName(
                    "github"
            );

    private static final OperationName OPERATION_NAME =
            new OperationName(
                    "create_issue"
            );

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash(
                    "a".repeat(64)
            );

    private static final String ISSUER =
            "proofmesh-control-plane";

    private static final String AUDIENCE =
            "proofmesh-gateway";

    private static final Instant ISSUED_AT =
            Instant.parse(
                    "2026-09-02T10:00:00Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-02T10:00:30Z"
            );

    @Test
    void validClaimsPreserveExactValues() {
        ExecutionGrantClaims claims =
                validClaims();

        assertThat(
                claims.grantId()
        ).isEqualTo(
                GRANT_ID
        );

        assertThat(
                claims.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                claims.agentId()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                claims.governedActionId()
        ).isEqualTo(
                GOVERNED_ACTION_ID
        );

        assertThat(
                claims.governanceDecisionId()
        ).isEqualTo(
                GOVERNANCE_DECISION_ID
        );

        assertThat(
                claims.toolName()
        ).isEqualTo(
                TOOL_NAME
        );

        assertThat(
                claims.operationName()
        ).isEqualTo(
                OPERATION_NAME
        );

        assertThat(
                claims.requestPayloadHash()
        ).isEqualTo(
                REQUEST_PAYLOAD_HASH
        );

        assertThat(
                claims.issuer()
        ).isEqualTo(
                ISSUER
        );

        assertThat(
                claims.audience()
        ).isEqualTo(
                AUDIENCE
        );

        assertThat(
                claims.issuedAt()
        ).isEqualTo(
                ISSUED_AT
        );

        assertThat(
                claims.expiresAt()
        ).isEqualTo(
                EXPIRES_AT
        );
    }

    @Test
    void rejectsNullGrantId() {
        assertThatThrownBy(
                () -> claimsWithGrantId(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "grantId must not be null"
                );
    }

    @Test
    void rejectsNullOrganizationId() {
        assertThatThrownBy(
                () -> claimsWithOrganizationId(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "organizationId must not be null"
                );
    }

    @Test
    void rejectsNullAgentId() {
        assertThatThrownBy(
                () -> claimsWithAgentId(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "agentId must not be null"
                );
    }

    @Test
    void rejectsNullGovernedActionId() {
        assertThatThrownBy(
                () -> claimsWithGovernedActionId(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "governedActionId must not be null"
                );
    }

    @Test
    void rejectsNullGovernanceDecisionId() {
        assertThatThrownBy(
                () -> claimsWithGovernanceDecisionId(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "governanceDecisionId must not be null"
                );
    }

    @Test
    void rejectsNullToolName() {
        assertThatThrownBy(
                () -> claimsWithToolName(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "toolName must not be null"
                );
    }

    @Test
    void rejectsNullOperationName() {
        assertThatThrownBy(
                () -> claimsWithOperationName(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "operationName must not be null"
                );
    }

    @Test
    void rejectsNullRequestPayloadHash() {
        assertThatThrownBy(
                () -> claimsWithRequestPayloadHash(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "requestPayloadHash must not be null"
                );
    }

    @Test
    void rejectsNullIssuer() {
        assertThatThrownBy(
                () -> claimsWithIssuer(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "issuer must not be null"
                );
    }

    @Test
    void rejectsBlankIssuer() {
        assertThatThrownBy(
                () -> claimsWithIssuer(" ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "issuer must not be blank"
                );
    }

    @Test
    void rejectsNullAudience() {
        assertThatThrownBy(
                () -> claimsWithAudience(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "audience must not be null"
                );
    }

    @Test
    void rejectsBlankAudience() {
        assertThatThrownBy(
                () -> claimsWithAudience(" ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "audience must not be blank"
                );
    }

    @Test
    void rejectsNullIssuedAt() {
        assertThatThrownBy(
                () -> claimsWithIssuedAt(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "issuedAt must not be null"
                );
    }

    @Test
    void rejectsNullExpiresAt() {
        assertThatThrownBy(
                () -> claimsWithExpiresAt(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "expiresAt must not be null"
                );
    }

    @Test
    void rejectsEqualIssuedAtAndExpiresAt() {
        assertThatThrownBy(
                () -> claimsWithExpiresAt(ISSUED_AT)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "expiresAt must be after issuedAt"
                );
    }

    @Test
    void rejectsExpiresAtBeforeIssuedAt() {
        assertThatThrownBy(
                () -> claimsWithExpiresAt(
                        ISSUED_AT.minusSeconds(1)
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "expiresAt must be after issuedAt"
                );
    }

    private static ExecutionGrantClaims validClaims() {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithGrantId(
            ExecutionGrantId grantId
    ) {
        return new ExecutionGrantClaims(
                grantId,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithOrganizationId(
            UUID organizationId
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                organizationId,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithAgentId(
            UUID agentId
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                agentId,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithGovernedActionId(
            UUID governedActionId
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                governedActionId,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithGovernanceDecisionId(
            UUID governanceDecisionId
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                governanceDecisionId,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithToolName(
            ToolName toolName
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                toolName,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithOperationName(
            OperationName operationName
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                operationName,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithRequestPayloadHash(
            RequestPayloadHash requestPayloadHash
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                requestPayloadHash,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithIssuer(
            String issuer
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                issuer,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithAudience(
            String audience
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                audience,
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithIssuedAt(
            Instant issuedAt
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                issuedAt,
                EXPIRES_AT
        );
    }

    private static ExecutionGrantClaims claimsWithExpiresAt(
            Instant expiresAt
    ) {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                expiresAt
        );
    }
}
