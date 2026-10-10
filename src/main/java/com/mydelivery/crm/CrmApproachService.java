package com.mydelivery.crm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CrmApproachService {
    private final List<Map<String,Object>> catalog;
    public CrmApproachService(ObjectMapper mapper) throws java.io.IOException {
        try (var in = new ClassPathResource("crm/approaches.json").getInputStream()) {
            catalog = mapper.readValue(in, new TypeReference<>() {});
        }
    }
    public List<Map<String,Object>> catalog() { return catalog; }
    @SuppressWarnings("unchecked")
    public Map<String,Object> prepare(Map<String,Object> profile, String requested) {
        var eligible = catalog.stream().filter(item -> ((Map<String,Object>) item.get("facts")).entrySet().stream()
            .allMatch(f -> Objects.equals(profile.get(f.getKey()), f.getValue()))).toList();
        var chosen = eligible.stream().filter(item -> requested == null || requested.isBlank() || requested.equals(item.get("id")))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Abordagem sem fatos suficientes na qualificação."));
        String name = CrmIdentity.text(profile.get("nome"));
        String greeting = name.isBlank() ? "Olá! Tudo bem?" : "Olá, equipe da " + name + "! Tudo bem?";
        String message = greeting + "\n\nSou da equipe comercial do MyDelivery. " + chosen.get("benefit")
            + "\n\nVocês podem experimentar por 32 dias grátis. Posso mostrar como funcionaria na operação de vocês?";
        return Map.of("mensagem", message, "abordagem", chosen.get("id"), "fatos", chosen.get("facts"));
    }
}
