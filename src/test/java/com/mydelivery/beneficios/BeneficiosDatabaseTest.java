package com.mydelivery.beneficios;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.service.AssinaturaService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeneficiosDatabaseTest {
    JdbcTemplate db;TransactionTemplate tx;MyCashService cash;MyMoneyService money;
    RestauranteRepository restaurants;AssinaturaRepository subscriptions;AssinaturaService subscriptionService;
    final String phone="11987654321",uuid=UUID.randomUUID().toString(),secret="a".repeat(64);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE restaurantes(id BIGINT PRIMARY KEY,nome VARCHAR(100))");
        db.execute("CREATE TABLE produtos(id BIGINT PRIMARY KEY,restaurante_id BIGINT,nome VARCHAR(100),preco DECIMAL(10,2))");
        db.execute("CREATE TABLE clientes(id BIGINT PRIMARY KEY,restaurante_id BIGINT,device_uuid VARCHAR(36),telefone VARCHAR(20),nome VARCHAR(100))");
        db.execute("CREATE TABLE pedidos(id BIGINT PRIMARY KEY,status VARCHAR(30),tipo VARCHAR(20),pago BOOLEAN,subtotal DECIMAL(10,2),desconto DECIMAL(10,2),cliente_id BIGINT,atualizado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE pedido_itens(pedido_id BIGINT,produto_id BIGINT,subtotal DECIMAL(10,2))");
        db.execute("CREATE TABLE pagamentos(id BIGINT PRIMARY KEY,pedido_id BIGINT,status VARCHAR(30))");
        db.execute("CREATE TABLE assinaturas(id BIGINT PRIMARY KEY,restaurante_id BIGINT,status VARCHAR(30),trial_fim TIMESTAMP)");
        db.execute("CREATE TABLE pagamentos_mensalidade(id BIGINT PRIMARY KEY,restaurante_id BIGINT,status VARCHAR(30),valor DECIMAL(10,2),pago_em TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V18__mycash_mymoney.sql"),new ClassPathResource("db/migration/V19__mymoney_saldo_indicacoes.sql")).execute(ds);
        db.update("INSERT INTO restaurantes VALUES(1,'Loja A'),(2,'Loja B')");
        db.update("INSERT INTO produtos VALUES(10,1,'Hambúrguer',30),(11,1,'Bebida',10),(20,2,'Outra loja',50)");
        cash=new MyCashService(db);restaurants=mock(RestauranteRepository.class);subscriptions=mock(AssinaturaRepository.class);subscriptionService=mock(AssinaturaService.class);money=new MyMoneyService(db,restaurants,subscriptions,subscriptionService);
        when(subscriptionService.concederMesesGratis(any(),anyInt(),anyString())).thenAnswer(i->Assinatura.builder().validaAte(LocalDateTime.now().plusMonths(i.getArgument(1,Integer.class))).proximaCobranca(LocalDateTime.now().plusMonths(i.getArgument(1,Integer.class))).build());
        when(subscriptions.findLockedByRestauranteId(anyLong())).thenReturn(Optional.of(new Assinatura()));
        when(restaurants.findById(anyLong())).thenAnswer(i->Optional.of(Restaurante.builder().id(i.getArgument(0,Long.class)).nome("Loja "+i.getArgument(0)).build()));
        run(()->cash.configure(1L,true,List.of(10L),false,new BigDecimal("10")));
    }
    <T>T in(Supplier<T> task){return tx.execute(s->task.get());}
    void run(Runnable task){tx.executeWithoutResult(s->task.run());}
    NovoPedidoRequest request(BigDecimal use,String device,String sec,String ph){var r=new NovoPedidoRequest();var c=new NovoPedidoRequest.ClienteDto();c.setTelefone(ph);c.setDeviceUuid(device);r.setCliente(c);r.setMycashSegredo(sec);r.setMycashUsado(use);r.setMycashRequestKey(UUID.randomUUID().toString());return r;}
    void order(long id,BigDecimal use){var req=request(use,uuid,secret,phone);create(id,req);}
    void create(long id,NovoPedidoRequest req){run(()->{cash.prepare(1,req);db.update("INSERT INTO pedidos(id,status,tipo,pago,subtotal,desconto) VALUES(?,'PENDENTE','DELIVERY',FALSE,30,0)",id);db.update("INSERT INTO pedido_itens VALUES(?,10,30)",id);Pedido p=Pedido.builder().id(id).restaurante(Restaurante.builder().id(1L).build()).subtotal(new BigDecimal("30")).desconto(BigDecimal.ZERO).taxaEntrega(BigDecimal.ZERO).total(new BigDecimal("30")).status(Pedido.Status.PENDENTE).build();cash.apply(p,req);db.update("UPDATE pedidos SET mycash_usado=?,desconto=? WHERE id=?",p.getMycashUsado(),p.getDesconto(),id);cash.capture(p,req);});}
    void paid(long id){db.update("UPDATE pedidos SET pago=TRUE,status='ENTREGUE' WHERE id=?",id);run(()->cash.reconcileRestaurant(1));}
    BigDecimal balance(){return db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mycash_movimentos WHERE restaurante_id=1 AND telefone=?",BigDecimal.class,phone);}
    @Test void discountsAreAllocatedOnlyAcrossProductsAndRoundedConservatively(){assertEquals(new BigDecimal("2.40"),MyCashService.calculate(List.of(Map.of("subtotal",new BigDecimal("30"),"percentual",10),Map.of("subtotal",10,"percentual",2)),new BigDecimal("40"),new BigDecimal("10")));}
    @Test void unpaidAndDeliveredWithoutPaymentDoNotEarn(){order(1,BigDecimal.ZERO);run(()->cash.reconcileRestaurant(1));assertEquals(0,balance().signum());db.update("UPDATE pedidos SET status='ENTREGUE' WHERE id=1");run(()->cash.reconcileRestaurant(1));assertEquals(0,balance().signum());paid(1);assertEquals(new BigDecimal("3.00"),balance());run(()->cash.reconcileRestaurant(1));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mycash_movimentos",Integer.class));}
    @Test void newCustomerSeesZeroWithoutDeviceWarningAndEarnsAfterConfirmedDelivery(){
        var first=(Map<?,?>)in(()->cash.wallet(1,new MyCashService.Identity(phone,uuid,secret)));
        assertEquals(true,first.get("validado"));
        assertEquals(new BigDecimal("0.00"),first.get("saldo"));
        assertNotEquals(MyCashService.DEVICE_MESSAGE,first.get("mensagem"));
        order(1,BigDecimal.ZERO);
        paid(1);
        var second=(Map<?,?>)in(()->cash.wallet(1,new MyCashService.Identity(phone,uuid,secret)));
        assertEquals(true,second.get("validado"));
        assertEquals(new BigDecimal("3.00"),second.get("saldo"));
    }
    @Test void anotherDeviceNeedsTheSameKnownCustomerNameToUseEarnedBalance(){
        order(1,BigDecimal.ZERO);
        db.update("INSERT INTO clientes(id,restaurante_id,device_uuid,telefone,nome) VALUES(1,1,?,?,?)",uuid,phone,"Gabriel Souza");
        db.update("UPDATE pedidos SET cliente_id=1 WHERE id=1");
        paid(1);
        String otherDevice=UUID.randomUUID().toString();
        var matching=(Map<?,?>)in(()->cash.wallet(1,new MyCashService.Identity(phone,otherDevice,secret,"Gabriel Souza")));
        assertEquals(true,matching.get("validado"));
        assertEquals(new BigDecimal("3.00"),matching.get("saldo"));
        var different=(Map<?,?>)in(()->cash.wallet(1,new MyCashService.Identity(phone,otherDevice,secret,"Outro Nome")));
        assertEquals(false,different.get("validado"));
        assertEquals(new BigDecimal("0.00"),different.get("saldo"));
    }
    @Test void refundOverridesPaidFlagAndIsIdempotent(){order(1,BigDecimal.ZERO);paid(1);db.update("INSERT INTO pagamentos VALUES(1,1,'CANCELADO')");run(()->cash.reconcileRestaurant(1));run(()->cash.reconcileRestaurant(1));assertEquals(0,balance().signum());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM mycash_movimentos",Integer.class));}
    @Test void redemptionIsAtomicAndCancellationRestoresItExactlyOnce(){order(1,BigDecimal.ZERO);paid(1);order(2,new BigDecimal("2"));assertEquals(new BigDecimal("1.00"),balance());db.update("UPDATE pedidos SET status='CANCELADO' WHERE id=2");run(()->cash.reconcileRestaurant(1));run(()->cash.reconcileRestaurant(1));assertEquals(new BigDecimal("3.00"),balance());}
    @Test void newDeviceAndChangedPhoneCannotSpendExistingWallet(){order(1,BigDecimal.ZERO);paid(1);assertThrows(RuntimeException.class,()->run(()->cash.prepare(1,request(BigDecimal.ONE,UUID.randomUUID().toString(),secret,phone))));assertThrows(RuntimeException.class,()->run(()->cash.prepare(1,request(BigDecimal.ONE,uuid,"b".repeat(64),phone))));var r=request(BigDecimal.ZERO,uuid,secret,"11999998888");run(()->cash.prepare(1,r));assertThrows(RuntimeException.class,()->run(()->cash.prepare(1,request(BigDecimal.ONE,uuid,secret,phone))));}
    @Test void normalOrderRemainsAllowedWithPhoneConflict(){order(1,BigDecimal.ZERO);paid(1);assertDoesNotThrow(()->create(2,request(BigDecimal.ZERO,uuid,secret,"11999998888")));}
    @Test void storeIsolationAndOwnership(){order(1,BigDecimal.ZERO);paid(1);var w=(Map<?,?>)in(()->cash.wallet(2,new MyCashService.Identity(phone,uuid,secret)));assertEquals(new BigDecimal("0.00"),w.get("saldo"));assertEquals(false,w.get("ativo"));assertThrows(RuntimeException.class,()->run(()->cash.configure(1L,null,List.of(20L),false,BigDecimal.TEN)));}
    @Test void changedCampaignDoesNotRewriteOrderRate(){order(1,BigDecimal.ZERO);run(()->cash.configure(1L,null,List.of(10L),false,new BigDecimal("50")));paid(1);assertEquals(new BigDecimal("3.00"),balance());}
    @Test void orderEditReconcilesAndCanCreateDebtWhenEarnedCreditWasAlreadySpent(){order(1,BigDecimal.ZERO);paid(1);order(2,new BigDecimal("3"));db.update("UPDATE pedidos SET status='CANCELADO' WHERE id=1");run(()->cash.reconcileRestaurant(1));assertEquals(new BigDecimal("-3.00"),balance());assertThrows(RuntimeException.class,()->run(()->cash.prepare(1,request(BigDecimal.ONE,uuid,secret,phone))));}
    @Test void idempotencyKeyReturnsOnlyTheOriginalSecureOrder(){var r=request(BigDecimal.ZERO,uuid,secret,phone);create(1,r);assertEquals(1L,in(()->cash.prepare(1,r)));r.setMycashSegredo("b".repeat(64));assertThrows(RuntimeException.class,()->in(()->cash.prepare(1,r)));}
    @Test void concurrentRedemptionsCannotOverspend() throws Exception {order(1,BigDecimal.ZERO);paid(1);var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);List<Future<Boolean>> fs=new ArrayList<>();for(long n=2;n<=3;n++){final long oid=n;fs.add(pool.submit(()->{start.await();try{order(oid,new BigDecimal("3"));return true;}catch(RuntimeException e){return false;}}));}start.countDown();int success=0;for(var f:fs)if(f.get(15,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(0,balance().signum());}finally{pool.shutdownNow();}}
    void subscriber(long rid){db.update("INSERT INTO assinaturas(id,restaurante_id,status,trial_fim) VALUES(?,?,'ATIVA',?)",rid,rid,LocalDateTime.now().minusDays(1));db.update("INSERT INTO pagamentos_mensalidade VALUES(?,?,'PAGO',75,?)",rid,rid,LocalDateTime.now());}
    String enroll(){subscriber(1);return (String)in(()->money.dashboard(1,false)).get("codigo");}
    void refer(long rid,String code,boolean paid){db.update("INSERT INTO restaurantes VALUES(?,?)",rid,"Indicada "+rid);if(paid)subscriber(rid);else db.update("INSERT INTO assinaturas(id,restaurante_id,status,trial_fim) VALUES(?,?,'TRIAL',?)",rid,rid,LocalDateTime.now().plusDays(32));run(()->money.attribute(Restaurante.builder().id(rid).build(),code));}
    @Test void trialAndCardOnlyDoNotValidate(){String code=enroll();refer(3,code,false);db.update("INSERT INTO pagamentos_mensalidade VALUES(3,3,'PENDENTE',75,NULL)");run(money::reconcile);assertEquals("PENDENTE",db.queryForObject("SELECT status FROM mymoney_indicacoes",String.class));assertFalse(money.eligible(3));verifyNoInteractions(subscriptionService);}
    @Test void firstPaidSubscriptionAwards25OnceAndFourReferralsUnlockOneMonth(){String code=enroll();for(long i=3;i<=6;i++)refer(i,code,true);run(money::reconcile);run(money::reconcile);assertEquals(new BigDecimal("100.00"),db.queryForObject("SELECT SUM(valor) FROM mymoney_indicacoes",BigDecimal.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mymoney_premios",Integer.class));verify(subscriptionService,times(1)).concederMesesGratis(any(),eq(1),anyString());}
    @Test void cumulativeGoalsAwardEachThresholdOnce(){String code=enroll();for(long i=3;i<=22;i++)refer(i,code,true);run(money::reconcile);run(money::reconcile);assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM mymoney_premios",Integer.class));assertEquals(5,db.queryForObject("SELECT SUM(meses) FROM mymoney_premios",Integer.class));assertEquals(new BigDecimal("700.00"),db.queryForObject("SELECT SUM(pix) FROM mymoney_premios",BigDecimal.class));}
    @Test void selfReferralAndDoubleOriginAreRejected(){String code=enroll();assertThrows(RuntimeException.class,()->run(()->money.attribute(Restaurante.builder().id(1L).build(),code)));assertThrows(RuntimeException.class,()->run(()->money.attribute(Restaurante.builder().id(2L).afiliadoCodigo("AFF").build(),code)));}
    @Test void suspiciousPhoneWaitsForAdminReview(){String code=enroll();when(restaurants.findById(1L)).thenReturn(Optional.of(Restaurante.builder().id(1L).telefone("11987654321").build()));run(()->money.attribute(Restaurante.builder().id(2L).telefone("+55 11 98765-4321").build(),code));assertEquals("EM_REVISAO",db.queryForObject("SELECT status FROM mymoney_indicacoes",String.class));}
    @Test void popupClaimsArePersistedAndWeeklyLimited(){enroll();assertEquals(true,in(()->money.dashboard(1,true)).get("mostrarPopup"));assertEquals(false,in(()->money.dashboard(1,true)).get("mostrarPopup"));assertEquals(false,in(()->money.dashboard(2,true)).get("mostrarPopup"));LocalDate today=LocalDate.now(BeneficiosSupport.ZONE),monday=today.with(DayOfWeek.MONDAY);db.update("DELETE FROM mymoney_popup");for(int i=0;i<3;i++)db.update("INSERT INTO mymoney_popup(restaurante_id,dia) VALUES(1,?)",monday.plusDays(i));assertEquals(false,in(()->money.dashboard(1,true)).get("mostrarPopup"));}
    @Test void refundedReferralBlocksUnpaidPrizesAndNeverRevalidates(){String code=enroll();for(long i=3;i<=10;i++)refer(i,code,true);run(money::reconcile);db.update("UPDATE pagamentos_mensalidade SET status='CANCELADO' WHERE id=3");run(money::reconcile);assertEquals("ESTORNADA",db.queryForObject("SELECT status FROM mymoney_indicacoes WHERE indicado_id=3",String.class));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM mymoney_premios WHERE revisao_necessaria=TRUE",Integer.class));db.update("INSERT INTO pagamentos_mensalidade VALUES(100,3,'PAGO',75,CURRENT_TIMESTAMP)");run(money::reconcile);assertEquals("ESTORNADA",db.queryForObject("SELECT status FROM mymoney_indicacoes WHERE indicado_id=3",String.class));}
    @Test void increasingAnAlreadyPaidOrderDoesNotInventAdditionalPaidValue(){order(1,BigDecimal.ZERO);paid(1);db.update("UPDATE pedidos SET subtotal=60 WHERE id=1");db.update("UPDATE pedido_itens SET subtotal=60 WHERE pedido_id=1");run(()->cash.reconcileRestaurant(1));assertEquals(new BigDecimal("3.00"),balance());}
    @Test void reducingProductsAdjustsEarnedCredit(){order(1,BigDecimal.ZERO);paid(1);db.update("UPDATE pedidos SET subtotal=15 WHERE id=1");db.update("UPDATE pedido_itens SET subtotal=15 WHERE pedido_id=1");run(()->cash.reconcileRestaurant(1));assertEquals(new BigDecimal("1.50"),balance());}
    @Test void pixRequiresRecipientAndCannotBeMarkedTwice(){String code=enroll();for(long i=3;i<=10;i++)refer(i,code,true);run(money::reconcile);long prize=db.queryForObject("SELECT id FROM mymoney_premios WHERE pix>0",Long.class);assertThrows(RuntimeException.class,()->run(()->money.pay(prize,null,LocalDateTime.now(BeneficiosSupport.ZONE),"comprovante-123","admin")));run(()->money.recipient(1,"Loja A","12345678901","chave-pix"));run(()->money.pay(prize,null,LocalDateTime.now(BeneficiosSupport.ZONE),"comprovante-123","admin"));run(()->money.pay(prize,null,LocalDateTime.now(BeneficiosSupport.ZONE),"comprovante-123","admin"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mymoney_auditoria WHERE acao='PIX_MARCADO_PAGO'",Integer.class));}
    @Test void unchangedCashOrdersAreNotScannedAgain(){order(1,BigDecimal.ZERO);paid(1);assertTrue(cash.restaurantsToReconcile().isEmpty());db.update("INSERT INTO pagamentos VALUES(1,1,'CANCELADO')");assertEquals(List.of(1L),cash.restaurantsToReconcile());}

    @Test void concurrentPaymentReconciliationDoesNotDuplicateRewards() throws Exception {String code=enroll();for(long i=3;i<=6;i++)refer(i,code,true);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->run(money::reconcile));var b=pool.submit(()->run(money::reconcile));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mymoney_premios",Integer.class));verify(subscriptionService,times(1)).concederMesesGratis(any(),eq(1),anyString());}finally{pool.shutdownNow();}}
    @Test void grantFailureRollsBackValidationAndCanSafelyRetry(){String code=enroll();for(long i=3;i<=6;i++)refer(i,code,true);when(subscriptionService.concederMesesGratis(any(),anyInt(),anyString())).thenThrow(new RuntimeException("Falha simulada"));assertThrows(RuntimeException.class,()->run(money::reconcile));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM mymoney_indicacoes WHERE status='VALIDADA'",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM mymoney_premios",Integer.class));}

    BigDecimal moneyBalance(){return db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mymoney_movimentos WHERE restaurante_id=1",BigDecimal.class);}
    void pixRecipient(){run(()->money.recipient(1,"Loja A","12345678901","chave-pix"));}
    long withdraw(BigDecimal amount,String key){return ((Number)((Map<?,?>)in(()->money.withdraw(1,amount,key))).get("id")).longValue();}
    @Test void oneReferralHas25WithdrawableWithoutAnyGoal(){String code=enroll();refer(3,code,true);run(money::reconcile);run(money::reconcile);assertEquals(new BigDecimal("25.00"),moneyBalance());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM mymoney_premios",Integer.class));pixRecipient();long id=withdraw(new BigDecimal("25"),UUID.randomUUID().toString());assertTrue(id>0);assertEquals(0,moneyBalance().signum());}
    @Test void twoReferralsHave50WithdrawableWithoutAnyGoal(){String code=enroll();refer(3,code,true);refer(4,code,true);run(money::reconcile);assertEquals(new BigDecimal("50.00"),moneyBalance());verifyNoInteractions(subscriptionService);}
    @Test void withdrawalIsIdempotentAndCannotOverspend(){String code=enroll();refer(3,code,true);run(money::reconcile);pixRecipient();String key=UUID.randomUUID().toString();long id=withdraw(new BigDecimal("25"),key);assertEquals(id,withdraw(new BigDecimal("25"),key));assertThrows(RuntimeException.class,()->withdraw(BigDecimal.ONE,UUID.randomUUID().toString()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mymoney_saques",Integer.class));}
    @Test void concurrentWithdrawalsDoNotReserveTheSameBalance() throws Exception {String code=enroll();refer(3,code,true);run(money::reconcile);pixRecipient();var pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> task=()->{try{withdraw(new BigDecimal("25"),UUID.randomUUID().toString());return true;}catch(RuntimeException e){return false;}};var a=pool.submit(task);var b=pool.submit(task);assertEquals(1,(a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0));assertEquals(0,moneyBalance().signum());}finally{pool.shutdownNow();}}
    @Test void refundBlocksReservedWithdrawalUntilCancellation(){String code=enroll();refer(3,code,true);run(money::reconcile);pixRecipient();long id=withdraw(new BigDecimal("25"),UUID.randomUUID().toString());db.update("UPDATE pagamentos_mensalidade SET status='CANCELADO' WHERE id=3");run(money::reconcile);assertEquals(new BigDecimal("-25.00"),moneyBalance());assertThrows(RuntimeException.class,()->run(()->money.payWithdrawal(id,null,LocalDateTime.now(BeneficiosSupport.ZONE),"receipt-123","admin")));run(()->money.cancelWithdrawal(id,"Pagamento de origem estornado","admin"));run(()->money.cancelWithdrawal(id,"Pagamento de origem estornado","admin"));assertEquals(0,moneyBalance().signum());}
    @Test void withdrawalPaymentPreservesRecipientAndCannotPayTwice(){String code=enroll();refer(3,code,true);run(money::reconcile);pixRecipient();long id=withdraw(new BigDecimal("25"),UUID.randomUUID().toString());run(()->money.recipient(1,"Outro Titular","98765432100","outra-chave"));run(()->money.payWithdrawal(id,null,LocalDateTime.now(BeneficiosSupport.ZONE),"receipt-123","admin"));run(()->money.payWithdrawal(id,null,LocalDateTime.now(BeneficiosSupport.ZONE),"receipt-123","admin"));assertEquals("chave-pix",db.queryForObject("SELECT pix_chave_snapshot FROM mymoney_saques WHERE id=?",String.class,id));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mymoney_auditoria WHERE acao='SAQUE_PAGO'",Integer.class));assertThrows(RuntimeException.class,()->run(()->money.cancelWithdrawal(id,"Não pode cancelar pago","admin")));}

}
