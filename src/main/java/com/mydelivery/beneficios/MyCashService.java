package com.mydelivery.beneficios;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.model.Pedido;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.util.*;
import static com.mydelivery.beneficios.BeneficiosSupport.*;

@Service
@RequiredArgsConstructor
public class MyCashService {
    private final JdbcTemplate db;
    private static final String STATE="CONCAT(p.status,'|',p.pago,'|',COALESCE(p.subtotal,0),'|',COALESCE(p.desconto,0),'|',p.mycash_usado,'|',COALESCE(CAST(p.atualizado_em AS CHAR),''),'|',COALESCE(pg.status,''))";
    private static final String CHANGED=" FROM mycash_pedidos s JOIN pedidos p ON p.id=s.pedido_id LEFT JOIN pagamentos pg ON pg.pedido_id=p.id WHERE COALESCE(s.estado_pedido,'')<>"+STATE;

    public static final String DEVICE_MESSAGE="Não foi possível validar seu myCash neste dispositivo. Você pode continuar o pedido sem usar o saldo.";
    private void lock(long rid) {db.queryForObject("SELECT id FROM restaurantes WHERE id=? FOR UPDATE",Long.class,rid);}
    private boolean active(long rid) {return db.queryForObject("SELECT COUNT(*) FROM mycash_config WHERE restaurante_id=? AND ativo=TRUE",Integer.class,rid)>0;}
    private BigDecimal balance(long rid,String phone) {return money(db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mycash_movimentos WHERE restaurante_id=? AND telefone=?",BigDecimal.class,rid,phone));}
    private boolean credential(String uuid,String secret) {return text(uuid).matches("[a-fA-F0-9-]{36}")&&text(secret).matches("[a-fA-F0-9]{64}");}
    private boolean trusted(long rid,String phone,String uuid,String secret) {
        if(!credential(uuid,secret))return false;
        return db.queryForObject("SELECT COUNT(*) FROM mycash_dispositivos WHERE restaurante_id=? AND uuid=? AND telefone=? AND segredo_hash=? AND confiavel=TRUE AND conflito=FALSE",Integer.class,rid,uuid,phone,hash(secret))==1;
    }
    private boolean sameEarnedCustomer(long rid,String ph,String name,String uuid,String secret) {
        if(!credential(uuid,secret)||text(name).isBlank())return false;
        return db.queryForObject("SELECT COUNT(*) FROM mycash_movimentos m JOIN pedidos p ON p.id=m.pedido_id JOIN clientes c ON c.id=p.cliente_id WHERE m.restaurante_id=? AND m.telefone=? AND m.tipo='CREDITO' AND LOWER(TRIM(c.nome))=LOWER(TRIM(?))",Integer.class,rid,ph,name)>0;
    }
    public record ProductRate(long produtoId, BigDecimal percentual) {}
    public record PublicProgram(boolean ativo, List<ProductRate> produtos) {}
    @Transactional(readOnly=true)
    public PublicProgram publicProgram(long rid) {
        boolean enabled=active(rid);
        var rates=enabled ? db.query("SELECT c.produto_id,c.percentual FROM mycash_produtos c JOIN produtos p ON p.id=c.produto_id AND p.restaurante_id=c.restaurante_id WHERE c.restaurante_id=? AND c.percentual>0 ORDER BY c.produto_id",
            (rs,n)->new ProductRate(rs.getLong("produto_id"),rs.getBigDecimal("percentual")),rid) : List.<ProductRate>of();
        return new PublicProgram(enabled,rates);
    }
    public record Identity(String telefone,String uuid,String segredo,String nome) {
        public Identity(String telefone,String uuid,String segredo) {this(telefone,uuid,segredo,null);}
    }
    @Transactional
    public Object wallet(long rid,Identity identity) {
        lock(rid);String ph=phone(identity.telefone());reconcilePhone(rid,ph);
        BigDecimal available=balance(rid,ph).max(BigDecimal.ZERO);
        boolean valid=available.signum()==0 || trusted(rid,ph,identity.uuid(),identity.segredo())
            || sameEarnedCustomer(rid,ph,identity.nome(),identity.uuid(),identity.segredo());
        return Map.of("ativo",active(rid),"validado",valid,"saldo",valid?available:money(0),
            "mensagem",valid?"Crédito exclusivo para pedidos nesta loja. Não pode ser sacado.":DEVICE_MESSAGE,
            "extrato",valid?db.queryForList("SELECT tipo,valor,saldo_apos,motivo,criado_em,pedido_id FROM mycash_movimentos WHERE restaurante_id=? AND telefone=? ORDER BY id DESC LIMIT 30",rid,ph):List.of());
    }
    /** Must run before the legacy customer UUID lookup mutates the customer's phone. */
    @Transactional
    public Long prepare(long rid,NovoPedidoRequest request) {
        BigDecimal wanted=money(request.getMycashUsado());
        if(wanted.signum()<0)throw bad("Valor de myCash inválido.");
        if(!active(rid)&&wanted.signum()==0)return null;
        String ph;
        try { ph=phone(request.getCliente().getTelefone()); }
        catch(Exception e) { if(wanted.signum()>0)throw bad(DEVICE_MESSAGE);return null; }
        lock(rid);String uuid=request.getCliente().getDeviceUuid(),secret=request.getMycashSegredo();
        if(!text(request.getMycashRequestKey()).isEmpty()) {
            if(!text(request.getMycashRequestKey()).matches("[a-fA-F0-9-]{36}"))throw bad("Atualize o pedido e tente novamente.");
            var existing=db.queryForList("SELECT s.pedido_id,s.restaurante_id,s.telefone,s.segredo_hash,p.status FROM mycash_pedidos s JOIN pedidos p ON p.id=s.pedido_id WHERE s.request_key=?",request.getMycashRequestKey());
            if(!existing.isEmpty()) {
                var row=existing.getFirst();
                if(id(row.get("restaurante_id"))!=rid||!ph.equals(row.get("telefone"))||!credential(uuid,secret)||!hash(secret).equals(row.get("segredo_hash")))throw bad(DEVICE_MESSAGE);
                if("CANCELADO".equals(row.get("status")))throw bad("Este pedido já foi cancelado. Atualize a página para fazer um novo pedido.");
                return id(row.get("pedido_id"));
            }
        }
        if(wanted.signum()>0) {
            if(text(request.getMycashRequestKey()).isEmpty())throw bad("Atualize o pedido e tente novamente.");
            reconcilePhone(rid,ph);
            if(!active(rid)||!(trusted(rid,ph,uuid,secret)
                || sameEarnedCustomer(rid,ph,request.getCliente().getNome(),uuid,secret)))throw bad(DEVICE_MESSAGE);
            if(balance(rid,ph).compareTo(wanted)<0)throw bad("Seu saldo myCash mudou. Confira o valor disponível e tente novamente.");
        }
        if(credential(uuid,secret)) {
            var rows=db.queryForList("SELECT * FROM mycash_dispositivos WHERE restaurante_id=? AND uuid=?",rid,uuid);
            if(rows.isEmpty()) {
                boolean conflict=false;
                for(String old:db.queryForList("SELECT telefone FROM clientes WHERE restaurante_id=? AND device_uuid=?",String.class,rid,uuid)) {
                    try {if(!ph.equals(phone(old))) conflict=true;}catch(Exception e){conflict=true;}
                }
                db.update("INSERT INTO mycash_dispositivos(restaurante_id,uuid,segredo_hash,telefone,conflito) VALUES(?,?,?,?,?)",rid,uuid,hash(secret),ph,conflict);
            } else if(hash(secret).equals(rows.getFirst().get("segredo_hash"))&&!ph.equals(rows.getFirst().get("telefone"))) {
                db.update("UPDATE mycash_dispositivos SET conflito=TRUE WHERE restaurante_id=? AND uuid=?",rid,uuid);
            }
        }
        return null;
    }
    /** Discount is included in Pedido.desconto so fiscal/payment/reporting paths see the real net sale. */
    public void apply(Pedido order,NovoPedidoRequest request) {
        BigDecimal wanted=money(request.getMycashUsado());
        if(wanted.signum()==0)return;
        BigDecimal cap=money(order.getSubtotal()).subtract(money(order.getDesconto())).max(BigDecimal.ZERO);
        if(wanted.compareTo(cap)>0)throw bad("O myCash pode ser usado até o valor dos produtos após os descontos.");
        order.setMycashUsado(wanted);order.setDesconto(money(order.getDesconto()).add(wanted));
        order.setTotal(money(order.getSubtotal()).subtract(order.getDesconto()).max(BigDecimal.ZERO).add(money(order.getTaxaEntrega())));
        if(order.getTotal().signum()==0) {
            order.setPago(true);order.setPagoEm(java.time.LocalDateTime.now());
            order.setModoPagamento(Pedido.ModoPagamento.NA_ENTREGA);
            if(order.getStatus()==Pedido.Status.AGUARDANDO_PAGAMENTO)order.setStatus(Pedido.Status.PENDENTE);
        }
    }
    @Transactional
    public void capture(Pedido order,NovoPedidoRequest request) {
        long rid=order.getRestaurante().getId();if(!active(rid)&&money(order.getMycashUsado()).signum()==0)return;
        String ph;
        try {ph=phone(request.getCliente().getTelefone());}catch(Exception e){return;}
        String uuid=request.getCliente().getDeviceUuid(),secret=request.getMycashSegredo();
        boolean credential=credential(uuid,secret);
        db.update("INSERT INTO mycash_pedidos(pedido_id,restaurante_id,telefone,dispositivo_uuid,segredo_hash,request_key,uso,base_liquida_max) VALUES(?,?,?,?,?,?,?,?)",
            order.getId(),rid,ph,credential?uuid:null,credential?hash(secret):null,text(request.getMycashRequestKey()).isEmpty()?null:request.getMycashRequestKey(),money(order.getMycashUsado()),money(order.getSubtotal()).subtract(money(order.getDesconto())).max(BigDecimal.ZERO));
        db.update("INSERT INTO mycash_regras_pedido(pedido_id,produto_id,percentual) SELECT DISTINCT ?,p.id,COALESCE(c.percentual,0) FROM produtos p JOIN pedido_itens i ON i.produto_id=p.id AND i.pedido_id=? LEFT JOIN mycash_produtos c ON c.produto_id=p.id WHERE p.restaurante_id=?",order.getId(),order.getId(),rid);
        if(money(order.getMycashUsado()).signum()>0) movement(order.getId(),rid,ph,"USO",order.getMycashUsado().negate(),0,"Desconto myCash reservado neste pedido");
    }
    @Transactional
    public void captureCounter(Pedido order) {
        if(order.getCliente()==null || !active(order.getRestaurante().getId()))return;
        lock(order.getRestaurante().getId());
        NovoPedidoRequest request=new NovoPedidoRequest();
        NovoPedidoRequest.ClienteDto client=new NovoPedidoRequest.ClienteDto();
        client.setTelefone(order.getCliente().getTelefone());request.setCliente(client);
        capture(order,request);
    }
    private void movement(long order,long rid,String ph,String kind,BigDecimal amount,int revision,String reason) {
        db.update("INSERT INTO mycash_movimentos(restaurante_id,telefone,pedido_id,tipo,valor,saldo_apos,motivo,revisao) VALUES(?,?,?,?,?,?,?,?)",rid,ph,order,kind,amount,balance(rid,ph).add(amount),reason,revision);
    }
    private void reconcilePhone(long rid,String ph) {
        for(Long oid:db.queryForList("SELECT s.pedido_id"+CHANGED+" AND s.restaurante_id=? AND s.telefone=? ORDER BY s.pedido_id",Long.class,rid,ph))reconcileOrder(oid);
    }
    @Transactional
    public void reconcileRestaurant(long rid) {
        lock(rid);for(Long oid:db.queryForList("SELECT s.pedido_id"+CHANGED+" AND s.restaurante_id=? ORDER BY s.pedido_id",Long.class,rid))reconcileOrder(oid);
    }
    public List<Long> restaurantsToReconcile() {return db.queryForList("SELECT DISTINCT s.restaurante_id"+CHANGED,Long.class);}
    private void reconcileOrder(long oid) {
        var p=db.queryForMap("SELECT id,status,tipo,pago,subtotal,desconto,mycash_usado FROM pedidos WHERE id=? FOR UPDATE",oid);
        var s=db.queryForMap("SELECT * FROM mycash_pedidos WHERE pedido_id=?",oid);
        long rid=id(s.get("restaurante_id"));String ph=text(s.get("telefone"));
        var payments=db.queryForList("SELECT status FROM pagamentos WHERE pedido_id=? FOR UPDATE",oid);
        boolean reversed="CANCELADO".equals(p.get("status"))||payments.stream().anyMatch(x->Set.of("CANCELADO","EXPIRADO").contains(text(x.get("status"))));
        boolean paymentRejected=payments.stream().anyMatch(x->"RECUSADO".equals(text(x.get("status"))));
        boolean complete=!paymentRejected&&yes(p.get("pago"))&&("ENTREGUE".equals(p.get("status"))||"BALCAO".equals(p.get("tipo")))&&!reversed;
        BigDecimal before=money(s.get("credito")),useBefore=money(s.get("uso"));
        BigDecimal use=reversed?money(0):money(p.get("mycash_usado"));
        BigDecimal target=money(0);
        if(complete) {
            var items=db.queryForList("SELECT i.subtotal,COALESCE(c.percentual,0) percentual FROM pedido_itens i LEFT JOIN mycash_regras_pedido c ON c.pedido_id=i.pedido_id AND c.produto_id=i.produto_id WHERE i.pedido_id=?",oid);
            BigDecimal subtotal=money(p.get("subtotal"));
            BigDecimal paidBase=subtotal.subtract(money(p.get("desconto"))).max(BigDecimal.ZERO).min(money(s.get("base_liquida_max")));
            target=calculate(items,subtotal,subtotal.subtract(paidBase));
        }
        int revision=((Number)s.get("revisao")).intValue()+1;
        if(before.compareTo(target)!=0) {
            BigDecimal diff=target.subtract(before);
            movement(oid,rid,ph,diff.signum()>0&&before.signum()==0?"CREDITO":reversed?"ESTORNO":"AJUSTE",diff,revision,reversed?"Crédito revertido por cancelamento/estorno":"Cashback sobre produtos líquidos com pagamento confirmado");
        }
        if(use.compareTo(useBefore)!=0) movement(oid,rid,ph,use.compareTo(useBefore)>0?"USO":"ESTORNO_USO",useBefore.subtract(use),revision,"Ajuste do saldo utilizado após atualização do pedido/pagamento");
        if(complete&&s.get("dispositivo_uuid")!=null) {
            // Only the original secure device can establish a new wallet. Existing funded
            // wallets are never claimed merely by supplying a phone or completing an order.
            int priorCredits=db.queryForObject("SELECT COUNT(*) FROM mycash_movimentos WHERE restaurante_id=? AND telefone=? AND pedido_id<>? AND tipo='CREDITO'",Integer.class,rid,ph,oid);
            if(priorCredits==0)db.update("UPDATE mycash_dispositivos SET confiavel=TRUE WHERE restaurante_id=? AND uuid=? AND segredo_hash=? AND telefone=? AND conflito=FALSE",rid,s.get("dispositivo_uuid"),s.get("segredo_hash"),ph);
        }
        if(before.compareTo(target)!=0||use.compareTo(useBefore)!=0)db.update("UPDATE mycash_pedidos SET credito=?,uso=?,revisao=? WHERE pedido_id=?",target,use,revision,oid);
        String state=db.queryForObject("SELECT "+STATE+" FROM pedidos p LEFT JOIN pagamentos pg ON pg.pedido_id=p.id WHERE p.id=?",String.class,oid);
        db.update("UPDATE mycash_pedidos SET estado_pedido=? WHERE pedido_id=?",state,oid);
    }
    /** Per-product rate after proportional coupon + wallet discount; delivery never enters the base. */
    public static BigDecimal calculate(List<Map<String,Object>> items,BigDecimal subtotal,BigDecimal discount) {
        if(subtotal.signum()<=0)return money(0);
        BigDecimal paid=subtotal.subtract(discount).max(BigDecimal.ZERO).min(subtotal),total=BigDecimal.ZERO;
        for(var i:items) {
            BigDecimal net=money(i.get("subtotal")).max(BigDecimal.ZERO).multiply(paid).divide(subtotal,12,RoundingMode.HALF_UP);
            total=total.add(net.multiply(money(i.get("percentual"))).divide(new BigDecimal("100"),2,RoundingMode.DOWN));
        }
        return money(total.min(paid));
    }
    /** Called by existing order editing flows. Preserve coupon + cap wallet use to remaining products. */
    public void adjusted(Pedido p) {
        BigDecimal oldUse=money(p.getMycashUsado());if(oldUse.signum()==0 && db.queryForObject("SELECT COUNT(*) FROM mycash_pedidos WHERE pedido_id=?",Integer.class,p.getId())==0)return;
        BigDecimal coupon=money(p.getDesconto()).subtract(oldUse).max(BigDecimal.ZERO).min(money(p.getSubtotal()));
        BigDecimal use=oldUse.min(money(p.getSubtotal()).subtract(coupon).max(BigDecimal.ZERO));
        p.setMycashUsado(use);p.setDesconto(coupon.add(use));p.setTotal(money(p.getSubtotal()).subtract(p.getDesconto()).add(money(p.getTaxaEntrega())));
    }
    @Transactional
    public Object config(long rid) {
        lock(rid);Map<String,Object> out=new LinkedHashMap<>();out.put("ativo",active(rid));
        out.put("produtos",db.queryForList("SELECT p.id,p.nome,p.preco,COALESCE(c.percentual,0) percentual FROM produtos p LEFT JOIN mycash_produtos c ON c.produto_id=p.id WHERE p.restaurante_id=? ORDER BY p.nome",rid));
        out.put("resumo",db.queryForMap("SELECT COALESCE(SUM(CASE WHEN tipo='CREDITO' THEN valor ELSE 0 END),0) creditado,COALESCE(-SUM(CASE WHEN tipo='USO' THEN valor ELSE 0 END),0) utilizado,COALESCE(SUM(valor),0) saldo FROM mycash_movimentos WHERE restaurante_id=?",rid));
        out.put("historico",db.queryForList("SELECT pedido_id,tipo,valor,saldo_apos,motivo,criado_em FROM mycash_movimentos WHERE restaurante_id=? ORDER BY id DESC LIMIT 50",rid));return out;
    }
    @Transactional
    public void configure(long rid,Boolean enabled,List<Long> products,boolean all,BigDecimal rate) {
        lock(rid);
        if(enabled!=null) {
            if(db.queryForObject("SELECT COUNT(*) FROM mycash_config WHERE restaurante_id=?",Integer.class,rid)==0)db.update("INSERT INTO mycash_config(restaurante_id,ativo) VALUES(?,?)",rid,enabled);
            else db.update("UPDATE mycash_config SET ativo=?,atualizado_em=CURRENT_TIMESTAMP WHERE restaurante_id=?",enabled,rid);
            db.update("INSERT INTO mycash_config_historico(restaurante_id,acao) VALUES(?,?)",rid,"Programa "+(enabled?"ativado":"desativado"));
        }
        if(rate==null)return;
        if(rate.signum()<0||rate.compareTo(new BigDecimal("100"))>0||rate.scale()>2)throw bad("Use um percentual entre 0 e 100, com até duas casas decimais.");
        List<Long> chosen=all?db.queryForList("SELECT id FROM produtos WHERE restaurante_id=?",Long.class,rid):products;
        if(chosen==null||chosen.isEmpty())throw bad("Selecione pelo menos um produto.");
        for(long pid:new LinkedHashSet<>(chosen)) {
            if(db.queryForObject("SELECT COUNT(*) FROM produtos WHERE id=? AND restaurante_id=?",Integer.class,pid,rid)==0)throw bad("Produto não pertence à loja.");
            if(db.queryForObject("SELECT COUNT(*) FROM mycash_produtos WHERE produto_id=?",Integer.class,pid)==0)db.update("INSERT INTO mycash_produtos(produto_id,restaurante_id,percentual) VALUES(?,?,?)",pid,rid,rate);
            else db.update("UPDATE mycash_produtos SET percentual=? WHERE produto_id=? AND restaurante_id=?",rate,pid,rid);
            db.update("INSERT INTO mycash_config_historico(restaurante_id,acao) VALUES(?,?)",rid,"Produto #"+pid+": "+rate+"%");
        }
    }
}
