package com.mydelivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.Pedido;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.fiscal.service.NfceEmissorService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Recebe somente os pedidos de balcão selecionados no painel Pedidos Mesas. */
@Service
@RequiredArgsConstructor
public class BalcaoCobrancaService {
    private final PedidoRepository pedidos;
    private final CaixaService caixa;
    private final ObjectMapper mapper;
    private final NfceEmissorService fiscal;
    public record Parte(Integer pessoa, String forma, BigDecimal valor) {}
    public record Cobranca(List<Long> pedidoIds, List<Parte> pagamentos) {}
    private ResponseStatusException invalido(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private long centavos(BigDecimal value) {
        try { return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact(); }
        catch (Exception e) { throw invalido("Informe valores válidos com até duas casas decimais."); }
    }
    @Transactional
    public Map<String,Object> cobrar(Long restauranteId, Cobranca request) {
        if(request==null || request.pedidoIds()==null || request.pedidoIds().isEmpty()
                || request.pedidoIds().size()>200 || request.pedidoIds().stream().anyMatch(Objects::isNull))
            throw invalido("Selecione de 1 a 200 pedidos para cobrar.");
        List<Long> ids=request.pedidoIds().stream().distinct().sorted().toList();
        if(ids.size()!=request.pedidoIds().size())throw invalido("Pedido repetido na seleção.");
        List<Pedido> selecionados=new ArrayList<>();
        // Ordem estável dos bloqueios impede duas confirmações simultâneas do mesmo pedido.
        for(Long id:ids){
            Pedido p=pedidos.findByIdForFiscal(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Pedido não encontrado."));
            if(!restauranteId.equals(p.getRestaurante().getId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            if(p.getTipo()!=Pedido.Tipo.BALCAO)throw invalido("Selecione somente pedidos de balcão.");
            if(Boolean.TRUE.equals(p.getPago()) || p.getStatus()==Pedido.Status.CANCELADO)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"O pedido #"+id+" já foi pago ou cancelado. Atualize a lista antes de cobrar.");
            if(p.getTotal()==null || centavos(p.getTotal())<=0)throw invalido("O pedido #"+id+" não tem um valor válido para cobrança.");
            selecionados.add(p);
        }
        long total=selecionados.stream().mapToLong(p->centavos(p.getTotal())).sum();
        List<Parte> partes=request.pagamentos()==null?List.of():request.pagamentos();
        if(partes.isEmpty() || partes.size()>30)throw invalido("Informe de 1 a 30 recebimentos.");
        long soma=0;Set<Integer> pessoas=new HashSet<>();
        for(Parte parte:partes){
            if(parte==null || parte.pessoa()==null || parte.pessoa()<1 || parte.pessoa()>30 || !pessoas.add(parte.pessoa()))throw invalido("Pessoa inválida na divisão.");
            if(!Set.of("PIX","DINHEIRO","CARTAO_CREDITO","CARTAO_DEBITO").contains(parte.forma()==null?"":parte.forma()))throw invalido("Forma de pagamento inválida.");
            long valor=centavos(parte.valor());if(valor<=0)throw invalido("Cada recebimento deve ser maior que zero.");
            soma=Math.addExact(soma,valor);
        }
        if(soma!=total)throw invalido("A soma dos recebimentos deve ser igual ao total dos pedidos selecionados.");
        int indice=0;long restante=centavos(partes.get(0).valor());
        for(Pedido pedido:selecionados){
            long alvo=centavos(pedido.getTotal());List<Map<String,Object>> rateio=new ArrayList<>();
            while(alvo>0){
                Parte parte=partes.get(indice);long valor=Math.min(alvo,restante);
                rateio.add(Map.of("pessoa",parte.pessoa(),"forma",parte.forma(),"valor",BigDecimal.valueOf(valor,2)));
                alvo-=valor;restante-=valor;
                if(restante==0 && ++indice<partes.size())restante=centavos(partes.get(indice).valor());
            }
            try{pedido.setPagamentosJson(mapper.writeValueAsString(rateio));}catch(Exception e){throw new IllegalStateException("Não foi possível salvar os recebimentos.",e);}
            pedido.setFormaPagamento(Pedido.FormaPagamento.valueOf((String)rateio.get(0).get("forma")));
            pedido.setPago(true);pedido.setPagoEm(LocalDateTime.now());
            // Mantém CONFIRMADO/PREPARO/PRONTO/ENTREGUE como já estava.
        }
        pedidos.saveAll(selecionados);
        for(Pedido pedido:selecionados)caixa.registrarVendaDoPedido(restauranteId,pedido);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){for(Pedido pedido:selecionados){try{fiscal.emitirParaPedidoSeguro(pedido.getId(),"sistema:cobrar-balcao",null);}catch(RuntimeException ignored){}}}
        });
        return Map.of("ok",true,"pedidosCobrados",selecionados.size(),"total",BigDecimal.valueOf(total,2));
    }
}
