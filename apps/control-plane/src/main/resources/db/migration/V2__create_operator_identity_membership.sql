CREATE TABLE operator_users (
    id UUID PRIMARY KEY,

    oidc_subject VARCHAR(255) NOT NULL
        UNIQUE,

    display_name VARCHAR(160),

    email VARCHAR(320),

    status VARCHAR(32) NOT NULL
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE organization_memberships (
    organization_id UUID NOT NULL
        REFERENCES organizations(id),

    user_id UUID NOT NULL
        REFERENCES operator_users(id),

    status VARCHAR(32) NOT NULL
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (organization_id, user_id)
);


CREATE INDEX idx_organization_memberships_user_id
    ON organization_memberships(user_id);