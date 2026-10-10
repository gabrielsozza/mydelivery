package com.mydelivery.service.google;

import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.PagamentoMensalidade;
import com.mydelivery.repository.PagamentoMensalidadeRepository;
import com.mydelivery.service.mercadopago.MercadoPagoClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Sends only settled platform subscriptions, never trials or saved-card scheduling. */
@Service
@lombok.extern.slf4j.Slf4j
public class GoogleRevenueService {
    private final PagamentoMensalidadeRepository pagamentos;
    private final MercadoPagoClient mp;
    private final RestClient google;
    private final String secret, measurementId, mpToken;

    public GoogleRevenueService(PagamentoMensalidadeRepository pagamentos, MercadoPagoClient mp,
        @Value("${GOOGLE_ANALYTICS_API_SECRET:}") String secret,
        @Value("${GOOGLE_ANALYTICS_MEASUREMENT_ID:G-367LSZTGFW}") String measurementId,
        @Value("${mydelivery.mercadopago.admin-access-token:${ADMIN_MP_ACCESS_TOKEN:}}") String mpToken) {
        this.pagamentos=pagamentos; this.mp=mp; this.secret=secret; this.measurementId=measurementId; this.mpToken=mpToken;
        var factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        this.google=RestClient.builder().requestFactory(factory).baseUrl("https://www.google-analytics.com").build();
    }

    @Async
    @Scheduled(fixedDelay=60000, initialDelay=90000)
    public synchronized void enviarPendentes() {
        // Inactive until the server-only GA4 credential is configured.
        if (secret.isBlank() || mpToken.isBlank() || !measurementId.matches("G-[A-Z0-9]+")) return;
        var now=LocalDateTime.now();
        var pending=pagamentos.pendentesGoogle(PagamentoMensalidade.Status.PAGO,now.minusHours(70),now.minusMinutes(10),PageRequest.of(0,10));
        for(var p:pending) {
            try {
                var current=pagamentos.findById(p.getId()).orElse(null);
                if(current==null || current.getGoogleEnviadoEm()!=null || current.getStatus()!=PagamentoMensalidade.Status.PAGO) continue;
                p=current;
                var r=p.getRestaurante();
                if(!Boolean.TRUE.equals(r.getGoogleMeasurementConsent()) || !Integer.valueOf(2).equals(r.getGoogleConsentVersion()) || r.getGoogleClientId()==null) continue;
                p.setGoogleTentativaEm(LocalDateTime.now());
                p.setGoogleTentativas(p.getGoogleTentativas()==null?1:p.getGoogleTentativas()+1);
                pagamentos.save(p);
                var receipt=mp.consultar(mpToken,p.getMpPaymentId());
                var payload=payload(p,receipt);
                if(payload==null) continue;
                google.post().uri(builder->builder.path("/mp/collect")
                    .queryParam("measurement_id",measurementId).queryParam("api_secret",secret).build())
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
                p.setGoogleEnviadoEm(LocalDateTime.now()); pagamentos.save(p);
            } catch(Exception ignored) {
                // Do not log credential-bearing URLs or payment/customer details. Retry from persisted queue.
                log.warn("[Google measurement] Receita pendente de envio; nova tentativa será agendada.");
            }
        }
    }

    static Map<String,Object> payload(PagamentoMensalidade p, MpPaymentResponse receipt) throws Exception {
        var r=p.getRestaurante();
        if(p.getStatus()!=PagamentoMensalidade.Status.PAGO || !Boolean.TRUE.equals(r.getGoogleMeasurementConsent())
            || !Integer.valueOf(2).equals(r.getGoogleConsentVersion()) || r.getGoogleClientId()==null || !r.getGoogleClientId().matches("[0-9]{1,30}\\.[0-9]{1,30}")
            || receipt==null || !"approved".equals(receipt.getStatus())
            || !Objects.equals(receipt.getId(),p.getMpPaymentId())
            || receipt.getExternalReference()==null || !receipt.getExternalReference().startsWith("assinatura-"+r.getId()+"-")
            || receipt.getTransactionAmount()==null || receipt.getTransactionAmount().signum()<=0) return null;
        // Consent and approval are absolute instants, independent of database/server local clocks.
        Instant approvedAt;
        try { approvedAt=OffsetDateTime.parse(receipt.getDateApproved()).toInstant(); }
        catch(Exception ignored) { return null; }
        if(r.getGoogleConsentAt()==null || approvedAt.isBefore(r.getGoogleConsentAt())) return null;
        var transaction=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("mydelivery-subscription:"+p.getMpPaymentId()).getBytes(StandardCharsets.UTF_8)));
        var value=receipt.getTransactionAmount();
        var params=new LinkedHashMap<String,Object>();
        params.put("transaction_id",transaction); params.put("value",value); params.put("currency","BRL");
        params.put("engagement_time_msec",1); params.put("site_area","owner_panel");
        params.put("items",List.of(Map.of("item_id",p.getPlano()==null?"subscription":p.getPlano().name(),"item_name","Assinatura MyDelivery","price",value,"quantity",1)));
        var body=new LinkedHashMap<String,Object>(); body.put("client_id",r.getGoogleClientId());
        body.put("timestamp_micros",approvedAt.toEpochMilli()*1000);
        body.put("consent",Map.of("ad_user_data","GRANTED","ad_personalization","DENIED"));
        body.put("events",List.of(Map.of("name","purchase","params",params)));
        return body;
    }
}
