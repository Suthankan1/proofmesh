package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.*;
import com.proofmesh.controlplane.agent.AgentCredentialService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Duration;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentCredentialIntegrationTest {
 @Autowired AgentCredentialService credentials;
 @Autowired JdbcTemplate jdbc;
 private final UUID org=UUID.randomUUID(), agent=UUID.randomUUID();
 @BeforeEach void fixtures(){
  jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",org,org.toString(),"Credential test");
  jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')",agent,org,"Test agent");
 }
 @Test void generatedSecretAuthenticatesExactTenantAndOnlyHashIsStored(){
  var issued=credentials.rotate(org,agent,Duration.ofDays(1));
  assertThat(issued.secret()).matches("pm_agent_[A-Za-z0-9_-]{43}");
  assertThat(issued.toString()).doesNotContain(issued.secret());
  var identity=credentials.authenticate(issued.secret()).orElseThrow();
  assertThat(identity.organizationId()).isEqualTo(org);assertThat(identity.agentId()).isEqualTo(agent);
  String hash=jdbc.queryForObject("SELECT credential_hash FROM proofmesh.agent_credentials WHERE id=?",String.class,issued.id());
  assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(issued.secret());
  assertThat(credentials.authenticate(issued.secret()+" ")).isEmpty();
  assertThat(credentials.authenticate("pm_agent_"+"a".repeat(43))).isEmpty();
  assertThat(credentials.authenticate(null)).isEmpty();
 }
 @Test void rotationRevokesOldCredential(){
  var first=credentials.rotate(org,agent,Duration.ofDays(1));
  var next=credentials.rotate(org,agent,Duration.ofDays(1));
  assertThat(next.secret()).isNotEqualTo(first.secret());
  assertThat(credentials.authenticate(first.secret())).isEmpty();
  assertThat(credentials.authenticate(next.secret())).isPresent();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.agent_credentials WHERE agent_id=? AND revoked_at IS NULL",Integer.class,agent)).isEqualTo(1);
 }
 @Test void wrongTenantCannotRotateOrBindCredential(){
  UUID other=UUID.randomUUID();
  jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",other,other.toString(),"Other tenant");
  assertThatThrownBy(()->credentials.rotate(other,agent,Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
  var valid=credentials.rotate(org,agent,Duration.ofDays(1));
  assertThatThrownBy(()->jdbc.update("UPDATE proofmesh.agent_credentials SET organization_id=? WHERE id=?",other,valid.id()))
   .isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void disabledAgentAndSuspendedOrganizationRejectAuthentication(){
  var issued=credentials.rotate(org,agent,Duration.ofDays(1));
  jdbc.update("UPDATE proofmesh.agents SET status='DISABLED' WHERE id=?",agent);
  assertThat(credentials.authenticate(issued.secret())).isEmpty();
  assertThatThrownBy(()->credentials.rotate(org,agent,Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
  jdbc.update("UPDATE proofmesh.agents SET status='ACTIVE' WHERE id=?",agent);
  jdbc.update("UPDATE proofmesh.organizations SET status='SUSPENDED' WHERE id=?",org);
  assertThat(credentials.authenticate(issued.secret())).isEmpty();
 }
 @Test
 @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void concurrentRotationsLeaveExactlyOneUsableCredential() throws Exception {
  var ready=new java.util.concurrent.CountDownLatch(2);
  var start=new java.util.concurrent.CountDownLatch(1);
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
   java.util.concurrent.Callable<com.proofmesh.controlplane.agent.IssuedAgentCredential> rotate=()->{
    ready.countDown();
    if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("rotation start timeout");
    return credentials.rotate(org,agent,Duration.ofDays(1));
   };
   var first=executor.submit(rotate);var second=executor.submit(rotate);
   assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
   var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
   var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
   int usable=(credentials.authenticate(a.secret()).isPresent()?1:0)+(credentials.authenticate(b.secret()).isPresent()?1:0);
   assertThat(usable).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.agent_credentials WHERE agent_id=? AND revoked_at IS NULL",Integer.class,agent)).isEqualTo(1);
  } finally {start.countDown();}
 }
 @Test void expiredCredentialsAndInvalidTtlFailClosed(){
  var issued=credentials.rotate(org,agent,Duration.ofDays(1));
  jdbc.update("UPDATE proofmesh.agent_credentials SET created_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE id=?",issued.id());
  assertThat(credentials.authenticate(issued.secret())).isEmpty();
  assertThatThrownBy(()->credentials.rotate(org,agent,Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->credentials.rotate(org,agent,Duration.ofDays(91))).isInstanceOf(IllegalArgumentException.class);
 }
}
