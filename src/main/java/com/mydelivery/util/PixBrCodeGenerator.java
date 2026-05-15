package com.mydelivery.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;

/**
 * Gera o "Pix Copia e Cola" no formato BR Code (padrão EMVCo do BACEN).
 *
 * O cliente cola essa string no app do banco e o pagamento é direcionado pra
 * chave PIX do recebedor. Padrão público — não precisa de gateway/PSP.
 *
 * Estrutura (ID + tamanho + valor):
 *  00 - Payload Format Indicator ("01")
 *  26 - Merchant Account Info (subcampos):
 *       00 = GUI ("br.gov.bcb.pix")
 *       01 = chave PIX
 *  52 - Merchant Category Code ("0000")
 *  53 - Currency ("986" = BRL)
 *  54 - Amount (ex: "10.50") - OPCIONAL
 *  58 - Country ("BR")
 *  59 - Merchant Name (até 25 chars, sem acento)
 *  60 - Merchant City (até 15 chars, sem acento)
 *  62 - Additional Data Field (subcampo 05 = TxID)
 *  63 - CRC16-CCITT-FALSE (4 hex uppercase)
 */
public final class PixBrCodeGenerator {

    private PixBrCodeGenerator() {}

    public static String gerar(String chavePix, String nomeRecebedor, String cidade,
                               BigDecimal valor, String txId) {
        if (chavePix == null || chavePix.isBlank())
            throw new IllegalArgumentException("Chave PIX é obrigatória");

        String nome = sanitize(nomeRecebedor, 25);
        String cid = sanitize(cidade, 15);
        String tx = sanitizeTxId(txId);

        // Campo 26 — Merchant Account Info (subcampos 00 + 01)
        String campo26 = tlv("00", "br.gov.bcb.pix") + tlv("01", chavePix);

        // Campo 62 — Additional Data Field (subcampo 05 = TxID)
        String campo62 = tlv("05", tx);

        StringBuilder payload = new StringBuilder();
        payload.append(tlv("00", "01"));               // Payload Format Indicator
        payload.append(tlv("26", campo26));            // Merchant Account Info
        payload.append(tlv("52", "0000"));             // MCC
        payload.append(tlv("53", "986"));              // BRL
        if (valor != null && valor.signum() > 0) {
            String v = valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
            payload.append(tlv("54", v));
        }
        payload.append(tlv("58", "BR"));
        payload.append(tlv("59", nome.isEmpty() ? "RECEBEDOR" : nome));
        payload.append(tlv("60", cid.isEmpty() ? "BRASIL" : cid));
        payload.append(tlv("62", campo62));

        // Campo 63 — CRC16 calculado sobre todo o payload + "6304"
        String comTag = payload.toString() + "6304";
        String crc = crc16(comTag);
        return comTag + crc;
    }

    /** Formata TLV (Tag-Length-Value) com tamanho zero-padded em 2 dígitos. */
    private static String tlv(String id, String valor) {
        if (valor == null) valor = "";
        int len = valor.length();
        return id + String.format("%02d", len) + valor;
    }

    /** Remove acentos e caracteres não alfanuméricos, mantendo só ASCII básico. */
    private static String sanitize(String s, int maxLen) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        String limpo = semAcento.replaceAll("[^A-Za-z0-9 ]", "").trim().toUpperCase();
        if (limpo.length() > maxLen) limpo = limpo.substring(0, maxLen);
        return limpo;
    }

    /** TxID: 1-25 caracteres alfanuméricos. */
    private static String sanitizeTxId(String s) {
        if (s == null || s.isBlank()) return "***";
        String limpo = s.replaceAll("[^A-Za-z0-9]", "");
        if (limpo.isEmpty()) return "***";
        if (limpo.length() > 25) limpo = limpo.substring(0, 25);
        return limpo;
    }

    /** CRC16-CCITT-FALSE: poly=0x1021, init=0xFFFF, no reflect, no xorout. */
    private static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes()) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
