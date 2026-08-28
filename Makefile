.PHONY: db-up db-down db-reset db-status control-test control-run

db-up:
	docker compose up -d postgres

db-down:
	docker compose down

db-reset:
	docker compose down -v
	docker compose up -d postgres

db-status:
	docker compose ps

control-test:
	cd apps/control-plane && ./mvnw clean verify

control-run:
	cd apps/control-plane && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

.PHONY: auth-up auth-stop auth-logs auth-status

auth-up:
	docker compose up -d keycloak

auth-stop:
	docker compose stop keycloak keycloak-db

auth-logs:
	docker compose logs -f keycloak

auth-status:
	docker compose ps keycloak keycloak-db