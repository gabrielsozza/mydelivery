package com.mydelivery.crm;

import com.mydelivery.model.WhatsappInstance;

/** The sole tenant allowed to use the commercial CRM. Never supplied by a request. */
public final class ProspectingPolicy {
    public static final long STORE_ID = 21L;
    private ProspectingPolicy() {}
    public static boolean isStore(Long id) { return id != null && id == STORE_ID; }
    public static boolean isInstance(WhatsappInstance instance) {
        return instance != null && instance.getRestaurante() != null && isStore(instance.getRestaurante().getId());
    }
    public static void requireInstance(WhatsappInstance instance) {
        if (!isInstance(instance)) throw new IllegalArgumentException("Sessão não pertence à loja comercial.");
    }
}
