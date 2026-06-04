package com.mydelivery.service;

import java.security.Security;
import java.util.Base64;
import java.util.List;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mydelivery.model.PushSubscription;
import com.mydelivery.repository.PushSubscriptionRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Utils;

/**
 * Envia notificações Web Push (com tela bloqueada / app fechado) usando
 * VAPID + FCM (gratuito).
 *
 * Setup ZERO custo (instruções pro operador):
 *   1) Subir endpoint /api/admin-internal/web-push/gerar-vapid 1× — devolve par de chaves
 *   2) Colocar no Railway env vars:
 *        WEB_PUSH_PUBLIC_KEY=base64url
 *        WEB_PUSH_PRIVATE_KEY=base64url
 *        WEB_PUSH_SUBJECT=mailto:contato@mydeliveryfood.com.br
 *   3) Re-deploy. Pronto. FCM/APNs gratuitos cuidam da entrega.
 *
 * Se as chaves não vierem por env, o service inicia DESABILITADO (loga
 * warning, todas as chamadas viram no-op). Resto do sistema segue normal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebPushService {

    private final PushSubscriptionRepository repo;

    @Value("${web.push.public-key:${WEB_PUSH_PUBLIC_KEY:}}")
    private String publicKey;

    @Value("${web.push.private-key:${WEB_PUSH_PRIVATE_KEY:}}")
    private String privateKey;

    @Value("${web.push.subject:${WEB_PUSH_SUBJECT:mailto:contato@mydeliveryfood.com.br}}")
    private String subject;

    private PushService pushService;
    private boolean habilitado;

    @PostConstruct
    public void init() {
        // BouncyCastle provider — exigido pelo lib pra ECDSA-P256 sob VAPID.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (publicKey == null || publicKey.isBlank() || privateKey == null || privateKey.isBlank()) {
            log.warn("[WebPush] DESABILITADO — WEB_PUSH_PUBLIC_KEY/PRIVATE_KEY não configuradas. " +
                     "Chame GET /api/admin-internal/web-push/gerar-vapid pra gerar e configure no Railway.");
            habilitado = false;
            return;
        }
        try {
            pushService = new PushService()
                    .setPublicKey(publicKey)
                    .setPrivateKey(privateKey)
                    .setSubject(subject);
            habilitado = true;
            log.info("[WebPush] habilitado, subject={}", subject);
        } catch (Exception e) {
            log.error("[WebPush] falha ao inicializar PushService: {}", e.getMessage());
            habilitado = false;
        }
    }

    public boolean isHabilitado() {
        return habilitado;
    }

    public String getPublicKey() {
        return publicKey;
    }

    /** Gera novo par VAPID. Usado pelo endpoint admin pra setup inicial. */
    public java.util.Map<String, String> gerarParVapid() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        // Gera par EC P-256 e codifica em base64url (padrão Web Push)
        var keyGen = java.security.KeyPairGenerator.getInstance("ECDH", "BC");
        keyGen.initialize(new org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec("secp256r1"));
        var keyPair = keyGen.generateKeyPair();

        byte[] pubBytes = Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) keyPair.getPublic());
        byte[] privBytes = Utils.encode((org.bouncycastle.jce.interfaces.ECPrivateKey) keyPair.getPrivate());

        return java.util.Map.of(
                "publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes),
                "privateKey", Base64.getUrlEncoder().withoutPadding().encodeToString(privBytes),
                "instrucao", "Copie esses valores para WEB_PUSH_PUBLIC_KEY e WEB_PUSH_PRIVATE_KEY no Railway, redeploy."
        );
    }

    /**
     * Envia notificação Web Push pra TODOS os aparelhos cadastrados desse
     * restaurante. Best-effort: erros 404/410 (subscription expirou) → apaga.
     * Demais erros → log e segue.
     *
     * @param titulo  notification title
     * @param corpo   body
     * @param url     URL a abrir ao clicar (ex: /pedidos.html?tipo=delivery)
     * @param tag     identificador (ex: "pedido-delivery") pra agrupar repetidas
     */
    public void notificar(Long restauranteId, String titulo, String corpo, String url, String tag) {
        if (!habilitado) return;
        List<PushSubscription> subs = repo.findByRestauranteId(restauranteId);
        if (subs.isEmpty()) return;

        String payload = "{\"title\":" + jsonStr(titulo)
                + ",\"body\":" + jsonStr(corpo)
                + ",\"url\":" + jsonStr(url == null ? "/painel.html" : url)
                + ",\"tag\":" + jsonStr(tag == null ? "mydelivery" : tag)
                + "}";

        for (PushSubscription s : subs) {
            try {
                var sub = new Subscription(s.getEndpoint(),
                        new Subscription.Keys(s.getP256dh(), s.getAuth()));
                var notif = new Notification(sub, payload);
                var resp = pushService.send(notif);
                int status = resp.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    log.info("[WebPush] subscription expirada (status={}) — removendo {}", status, s.getEndpoint());
                    repo.delete(s);
                } else if (status >= 400) {
                    log.warn("[WebPush] entrega falhou status={} endpoint={}", status, s.getEndpoint());
                }
            } catch (Exception e) {
                log.warn("[WebPush] erro enviando pra {}: {}", s.getEndpoint(), e.getMessage());
            }
        }
    }

    private String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
