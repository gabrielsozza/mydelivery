package com.mydelivery.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.dto.cardapio.ImportacaoConfirmRequest;
import com.mydelivery.dto.cardapio.ImportacaoPreviewDTO;
import com.mydelivery.model.Categoria;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Importador de cardápio — CSV/XLSX/PDF/IMAGEM (JPG, PNG, WEBP).
 *
 * Fluxo:
 *  1. Cliente envia arquivo → analisar() retorna preview editável
 *  2. Cliente revisa e confirma → confirmar() salva no banco
 *
 * Estratégia por formato:
 *  - CSV/XLSX → parser tabular com fuzzy match agressivo de colunas (sinônimos +
 *    Levenshtein como fallback)
 *  - PDF → PDFBox extrai texto, parser de texto livre infere produtos/preços/categorias
 *  - Imagem → OCR.space API (gratuito) extrai texto, reusa o parser de texto livre
 *
 * Resiliência:
 *  - Aliases ricos em PT/EN cobrindo formatos de Anotaaí, Olaclick, iFood, Goomer,
 *    99Food, Keeta, Anota.ai, planilhas genéricas e variações como "vlr_unit"
 *  - Fuzzy match com distância de edição (até 2 chars) pra pegar typos
 *  - Parser de preço aceita R$, vírgula, ponto, com/sem milhar
 *  - Heurísticas pra detectar categoria (TODO MAIÚSCULO, palavras-chave, ":")
 *  - Mesmo se OCR/parsing falhar parcialmente, retorna o que conseguir + avisos
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportacaoCardapioService {

    private final CategoriaRepository categoriaRepository;
    private final ProdutoRepository produtoRepository;
    private final RestauranteRepository restauranteRepository;
    private final CardapioService cardapioService;

    @Value("${mydelivery.ocr.api-key:helloworld}")
    private String ocrApiKey;

    @Value("${mydelivery.ocr.timeout-ms:60000}")
    private int ocrTimeoutMs;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ── Sinônimos pra detecção automática de coluna (PT + EN + variações) ─────
    // Todos LOWERCASE SEM ACENTO. O matching faz contains + Levenshtein.
    private static final String[] COL_NOME = {
        "nome", "name", "produto", "product", "item", "titulo", "title",
        "descricao do produto", "produto nome", "nm produto", "nome produto",
        "item nome", "nome do item", "produto descricao", "denominacao"
    };
    private static final String[] COL_DESC = {
        "descricao", "description", "detalhes", "details", "obs", "observacao",
        "ingredientes", "sobre", "comentario", "comentarios", "info",
        "informacoes", "resumo", "sumario", "longa", "longa descricao",
        "descricao longa", "descricao completa"
    };
    private static final String[] COL_PRECO = {
        "preco", "price", "valor", "valor unitario", "preco unitario", "custo",
        "amount", "valor produto", "preco produto", "vlr", "vlr unit",
        "vlr unitario", "vl unit", "vl unitario", "preco venda", "valor venda",
        "venda", "preco final", "valor final", "v. unit", "v unitario",
        "preco r$", "valor r$", "rs", "r$"
    };
    private static final String[] COL_CATEGORIA = {
        "categoria", "category", "grupo", "group", "secao", "section", "menu",
        "tipo", "type", "departamento", "familia", "classe", "classificacao",
        "categoria nome", "nome categoria", "grupo produto", "secao menu",
        "categoria produto"
    };
    private static final String[] COL_IMAGEM = {
        "imagem", "image", "foto", "photo", "img", "imagem url", "url imagem",
        "image url", "picture", "pic", "thumbnail", "thumb", "url foto",
        "link imagem", "link foto", "imgsrc", "imagem produto"
    };

    // Distância de Levenshtein máxima pra considerar "match aproximado" do header
    private static final int FUZZY_MAX_DIST = 2;

    // Regex de preço reutilizado por PDF e OCR de imagem
    private static final java.util.regex.Pattern PRECO_RE = java.util.regex.Pattern.compile(
            "(?:R\\$\\s*)?([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2})|(?:R\\$\\s*)([0-9]{1,4})\\b"
    );

    // ── ANÁLISE (preview) ────────────────────────────────────────────────────

    public ImportacaoPreviewDTO analisar(MultipartFile arquivo) {
        if (arquivo == null || arquivo.isEmpty())
            throw new RuntimeException("Arquivo vazio");

        String filename = arquivo.getOriginalFilename() != null
                ? arquivo.getOriginalFilename().toLowerCase()
                : "";
        String contentType = arquivo.getContentType() != null
                ? arquivo.getContentType().toLowerCase()
                : "";

        try {
            List<List<String>> linhas;
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                linhas = lerExcel(arquivo);
            } else if (filename.endsWith(".pdf")) {
                linhas = lerPdf(arquivo);
            } else if (ehImagem(filename, contentType)) {
                linhas = lerImagem(arquivo);
            } else if (filename.endsWith(".csv") || filename.endsWith(".txt")
                    || contentType.contains("csv") || contentType.contains("text/plain")) {
                linhas = lerCsv(arquivo);
            } else {
                // Fallback: tenta CSV (maioria dos casos sem extensão)
                linhas = lerCsv(arquivo);
            }
            return montarPreview(linhas);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("Erro ao analisar arquivo de importação", e);
            throw new RuntimeException("Não foi possível ler o arquivo: " + e.getMessage());
        }
    }

    private boolean ehImagem(String filename, String contentType) {
        return filename.endsWith(".jpg") || filename.endsWith(".jpeg")
            || filename.endsWith(".png") || filename.endsWith(".webp")
            || filename.endsWith(".bmp") || filename.endsWith(".gif")
            || filename.endsWith(".tiff") || filename.endsWith(".tif")
            || contentType.startsWith("image/");
    }

    // ── LEITURA DE IMAGEM (OCR.space) ───────────────────────────────────────

    /**
     * OCR via OCR.space API gratuita. Limite: 1MB por arquivo na chave pública
     * "helloworld". Pra arquivos maiores configure {@code mydelivery.ocr.api-key}
     * com uma chave pessoal (gratuita, 25k req/mês em https://ocr.space/ocrapi).
     */
    private List<List<String>> lerImagem(MultipartFile arquivo) throws IOException, InterruptedException {
        byte[] bytes = arquivo.getBytes();
        if (bytes.length > 1024 * 1024 && "helloworld".equals(ocrApiKey)) {
            throw new RuntimeException(
                "Imagem maior que 1MB exige chave OCR personalizada. " +
                "Comprima a imagem ou configure mydelivery.ocr.api-key. " +
                "Alternativa: tire foto em menor resolução ou use PDF/CSV.");
        }

        String mime = arquivo.getContentType() != null ? arquivo.getContentType() : "image/jpeg";
        if (mime.equalsIgnoreCase("application/octet-stream")) mime = "image/jpeg";

        // OCR.space aceita base64 image data via form field "base64Image"
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:" + mime + ";base64," + base64;

        // Form encoding
        StringBuilder form = new StringBuilder();
        form.append("apikey=").append(java.net.URLEncoder.encode(ocrApiKey, StandardCharsets.UTF_8));
        form.append("&language=por");
        form.append("&isOverlayRequired=false");
        form.append("&detectOrientation=true");
        form.append("&scale=true");
        // OCR Engine 2 = mais novo, melhor em layouts complexos (cardápios)
        form.append("&OCREngine=2");
        form.append("&base64Image=").append(java.net.URLEncoder.encode(dataUrl, StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.ocr.space/parse/image"))
                .timeout(Duration.ofMillis(ocrTimeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form.toString()))
                .build();

        HttpResponse<String> res = HTTP.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            log.warn("[OCR] status={} body={}", res.statusCode(),
                    res.body() != null ? res.body().substring(0, Math.min(300, res.body().length())) : "");
            throw new RuntimeException(
                "Serviço de OCR indisponível (status " + res.statusCode() + "). " +
                "Tente novamente em alguns minutos ou use CSV/Excel/PDF.");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(res.body());
        } catch (Exception e) {
            throw new RuntimeException("Resposta inválida do OCR. Tente novamente.");
        }

        if (root.path("IsErroredOnProcessing").asBoolean(false)) {
            String erro = root.path("ErrorMessage").isArray() && root.path("ErrorMessage").size() > 0
                    ? root.path("ErrorMessage").get(0).asText()
                    : root.path("ErrorMessage").asText("Erro desconhecido no OCR");
            throw new RuntimeException("OCR falhou: " + erro);
        }

        StringBuilder texto = new StringBuilder();
        JsonNode results = root.path("ParsedResults");
        if (results.isArray()) {
            for (JsonNode pr : results) {
                String t = pr.path("ParsedText").asText("");
                if (!t.isBlank()) texto.append(t).append("\n");
            }
        }

        String textoFinal = texto.toString().trim();
        if (textoFinal.isEmpty()) {
            throw new RuntimeException(
                "OCR não conseguiu extrair texto da imagem. Verifique se: " +
                "(1) o texto está nítido e em foco; (2) há contraste suficiente; " +
                "(3) imagem não está rotacionada. Dica: tire foto bem na frente do cardápio, com boa luz.");
        }

        log.info("[OCR] imagem={} bytes, texto extraído={} chars", bytes.length, textoFinal.length());
        return parsearTextoLivre(textoFinal);
    }

    // ── LEITURA DE PDF ───────────────────────────────────────────────────────

    private List<List<String>> lerPdf(MultipartFile arquivo) throws IOException {
        String texto;
        try (PDDocument doc = Loader.loadPDF(arquivo.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            texto = stripper.getText(doc);
        }

        if (texto == null || texto.isBlank())
            throw new RuntimeException("PDF parece estar vazio ou ser uma imagem escaneada (sem texto extraível). Tente subir como imagem (.jpg/.png) — usamos OCR pra ler.");

        return parsearTextoLivre(texto);
    }

    // Stopwords usadas pra inferir o "nome dominante" do cardápio.
    // Excluímos artigos, preposições, palavras de UI, slogans e unidades.
    // Set.copyOf(List.of(...)) tolera duplicatas (que Set.of() rejeita).
    private static final java.util.Set<String> STOPWORDS_NOMES = java.util.Set.copyOf(java.util.List.of(
        "de","do","da","dos","das","a","o","as","os","e","ou","um","uma","uns","umas",
        "com","sem","para","por","que","se","no","na","nos","nas","ao","aos",
        "este","esta","esse","essa","isto","isso","aqui","ali","la","ja",
        "mais","menos","muito","muita","muitos","muitas","todo","toda","todos","todas",
        "ser","estar","ter","fazer","ir","ver","dar","saber","poder","querer","incluir",
        "inclui","tem","contem","contendo","acompanha",
        "delivery","taxa","entrega","frete","monte","jeito","escolha","aproveite","melhor",
        "seu","sua","seus","suas","meu","minha","gostoso","saboroso","delicioso",
        "carinho","qualidade","ingredientes","cremoso","sabor","sabores","gostosa",
        "terapia","roxa","gente","ama","amamos","adoramos","amem","amam",
        "novo","nova","novos","novas","abertura","aberto","fechado","fechada",
        "horario","horarios","telefone","whatsapp","instagram","facebook","tiktok",
        "endereco","enderecos","rua","avenida","numero","cep","bairro","cidade",
        "produto","produtos","item","itens","cardapio","menu","cardapios",
        "preco","precos","valor","valores","real","reais","rs",
        "ml","g","kg","gr","cl","cm","mm","un","unid","unidade","unidades",
        "complemento","complementos","cobertura","coberturas","adicional","adicionais",
        "opcional","opcionais","extra","extras","grande","medio","pequeno",
        "sim","nao","talvez","quem","quando","onde","como","porque","pouco"
    ));

    // Palavras-chave que indicam que a linha NÃO é produto (taxa, contato, etc).
    private static final String[] LIXO_NOME = {
        "taxa entrega", "taxa de entrega", "taxa", "frete", "entrega gratis", "entrega gratuita",
        "abertura", "abrimos", "fechamos", "horario", "horarios",
        "telefone", "whatsapp", "instagram", "facebook", "endereco", "endereço",
        "monte do seu jeito", "monte seu", "escolha seus", "aproveite",
        "terapia roxa", "que a gente ama", "muito carinho", "ingredientes de qualidade",
        "cardapio", "menu", "siga nos", "siga-nos"
    };

    // Detecta "300ml", "500g", "1L", "2 litros" etc — variantes que precisam de prefixo.
    private static final java.util.regex.Pattern VARIANTE_RE = java.util.regex.Pattern.compile(
        "^\\s*\\d+\\s*(ml|g|kg|gr|cl|l|litros?|cm|mm|un|unid|unidades?|pessoas?|fatias?|pe[cç]as?)\\s*$",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    // Só unidade isolada, sem número (caso do OCR ter cortado o "300" e sobrado só "ml")
    private static final java.util.regex.Pattern UNIDADE_SOLO_RE = java.util.regex.Pattern.compile(
        "^\\s*(ml|g|kg|gr|cl|l|cm|mm|un|unid)\\s*$",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Parser de texto livre (PDF/OCR) → estrutura tabular [Categoria, Nome, Descrição, Preço].
     *
     * Estratégia inteligente:
     *  1. PRÉ-PASSO: infere o "nome dominante" do cardápio (palavra mais frequente
     *     não-stopword) — usado pra prefixar variantes soltas (ex: "300ml" → "Açaí 300ml")
     *     e pra renomear a categoria "Geral" quando todos os produtos pertencem ao mesmo tema.
     *  2. PARSE: percorre linhas mantendo um buffer de contexto (últimas 4 linhas)
     *     pra recuperar nome quando o OCR fragmenta texto.
     *  3. FILTROS: rejeita linhas-lixo (taxa, frete, slogans) e nomes inválidos.
     *  4. PÓS-PROCESSAMENTO: agrupa categoria única, absorve "Inclui:..." em descrição,
     *     deduplica e limpa.
     */
    private List<List<String>> parsearTextoLivre(String texto) {
        List<List<String>> linhas = new ArrayList<>();
        linhas.add(Arrays.asList("Categoria", "Nome", "Descrição", "Preço"));

        // Pré-passo: nome dominante (Açaí, Pizza, Hambúrguer...)
        String nomeDominante = inferirNomeDominante(texto);
        log.debug("[Import] nomeDominante detectado: {}", nomeDominante);

        String categoriaAtual = "Geral";
        java.util.LinkedList<String> contexto = new java.util.LinkedList<>();
        final int CTX_MAX = 4;

        // Lista intermediária pra permitir pós-processamento (linhas "Inclui:..." → descrição)
        List<String[]> produtos = new ArrayList<>();

        for (String rawLine : texto.split("\\r?\\n")) {
            String linha = rawLine.trim().replaceAll("\\s+", " ");
            if (linha.isEmpty()) {
                if (!contexto.isEmpty()) contexto.clear();
                continue;
            }

            java.util.regex.Matcher m = PRECO_RE.matcher(linha);
            if (m.find()) {
                String precoStr = m.group();
                String antes = linha.substring(0, m.start()).trim()
                                .replaceAll("[.\\-–·•\\s]+$", "").trim();
                String depois = linha.substring(m.end()).trim();

                // Tenta achar nome em ordem: antes-do-preço > contexto > nada
                String nome = pickNomeProduto(antes, contexto);
                String descricao = depois;

                // Nome > 80 chars com " - " no meio → racha em nome + descrição
                if (nome != null && nome.length() > 80 && nome.contains(" - ")) {
                    int idx = nome.indexOf(" - ");
                    descricao = (descricao.isEmpty() ? "" : descricao + " — ") + nome.substring(idx + 3).trim();
                    nome = nome.substring(0, idx).trim();
                }

                // Limpa lixo no início do nome (ex: bullets, pontuação, números soltos)
                if (nome != null) nome = nome.replaceAll("^[\\d\\W_]+(?=\\p{L})", "").trim();

                // Se o nome é só uma variante (300ml / ml solto) prefixa com nome dominante
                if (nome != null && nomeDominante != null
                        && (VARIANTE_RE.matcher(nome).matches() || UNIDADE_SOLO_RE.matcher(nome).matches())) {
                    nome = capitalize(nomeDominante) + " " + nome.toLowerCase();
                }

                // Linha-lixo (taxa de entrega, slogan, etc) → pula produto
                if (nome == null || nome.length() < 2 || ehLixo(nome)) {
                    contexto.clear();
                    continue;
                }

                produtos.add(new String[]{categoriaAtual, nome, descricao, precoStr});
                contexto.clear();
            } else {
                // Sem preço — pode ser categoria, descrição contextual ou nome pendente
                if (linha.length() <= 60 && linha.matches(".*[A-Za-zÀ-ÿ].*")) {
                    boolean maiusculas = linha.equals(linha.toUpperCase()) && linha.length() >= 4
                            && linha.matches(".*[A-ZÀ-Ý]{3,}.*")
                            && !VARIANTE_RE.matcher(linha).matches();
                    boolean ehCategoria = (maiusculas || linha.endsWith(":") || ehTituloCategoria(linha))
                            && !ehLixo(linha);
                    if (ehCategoria) {
                        categoriaAtual = linha.replaceAll("[:]+$", "").trim();
                        contexto.clear();
                        continue;
                    }
                }

                // "Inclui: 4 complementos + 1 cobertura" — guarda como descrição do último produto
                if (linha.toLowerCase().matches("^(inclui|acompanha|contem|vem com|com|com:)[\\s:].*")) {
                    if (!produtos.isEmpty()) {
                        String[] ult = produtos.get(produtos.size() - 1);
                        ult[2] = (ult[2] == null || ult[2].isBlank()) ? linha : ult[2] + " · " + linha;
                    }
                    continue;
                }

                // Senão, guarda como contexto pra próxima linha com preço
                contexto.addFirst(linha);
                while (contexto.size() > CTX_MAX) contexto.removeLast();
            }
        }

        // PÓS-PROCESSAMENTO ──────────────────────────────────────────
        // (1) Se categoria == "Geral" em TODOS e há nome dominante, usa nome dominante como categoria
        if (nomeDominante != null && !produtos.isEmpty()) {
            boolean todosGeral = produtos.stream().allMatch(p -> "Geral".equals(p[0]));
            if (todosGeral) {
                String catFinal = capitalize(nomeDominante);
                produtos.forEach(p -> p[0] = catFinal);
            }
        }

        // (2) Deduplica produtos exatamente iguais (nome + preço idêntico)
        java.util.Set<String> vistos = new java.util.HashSet<>();
        for (String[] p : produtos) {
            String chave = normalizar(p[1]) + "|" + p[3];
            if (vistos.contains(chave)) continue;
            vistos.add(chave);
            linhas.add(Arrays.asList(p));
        }

        if (linhas.size() == 1) {
            throw new RuntimeException(
                "Não consegui identificar nenhum produto. Dicas: (a) verifique se o texto está nítido " +
                "(b) preços precisam estar visíveis (R$ 10,00 / 10,00) (c) tente reformatar como CSV " +
                "usando nosso template.");
        }
        return linhas;
    }

    /**
     * Escolhe o melhor candidato a nome do produto baseado em:
     *  - parte da linha antes do preço (se ≥3 chars e não-lixo)
     *  - linhas do buffer de contexto (mais recente primeiro)
     *  - última opção: a parte antes do preço mesmo se curta (pra prefixar com dominante)
     */
    private String pickNomeProduto(String antesDoPreco, java.util.LinkedList<String> contexto) {
        if (antesDoPreco != null && antesDoPreco.length() >= 3 && !ehLixo(antesDoPreco)) {
            return antesDoPreco;
        }
        for (String c : contexto) {
            if (c == null || c.length() < 2) continue;
            if (ehLixo(c)) continue;
            // Combina contexto + variante (ex: "Açaí" em cima + "300ml" no preço)
            if (antesDoPreco != null && !antesDoPreco.isBlank()
                    && (VARIANTE_RE.matcher(antesDoPreco).matches() || UNIDADE_SOLO_RE.matcher(antesDoPreco).matches())) {
                return c + " " + antesDoPreco;
            }
            return c;
        }
        return (antesDoPreco != null && antesDoPreco.length() >= 2) ? antesDoPreco : null;
    }

    /** Detecta o "termo dominante" no texto — palavra significativa mais repetida. */
    private String inferirNomeDominante(String texto) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        String norm = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        // Token = sequência de letras (sem números, sem pontuação)
        for (String token : norm.split("[^a-z']+")) {
            if (token.length() < 3 || token.length() > 25) continue;
            if (STOPWORDS_NOMES.contains(token)) continue;
            freq.merge(token, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Verifica se a string parece ser linha de UI/lixo (taxa de entrega, slogan, contato). */
    private boolean ehLixo(String s) {
        if (s == null) return true;
        String norm = normalizar(s);
        if (norm.length() < 2) return true;
        for (String l : LIXO_NOME) {
            if (norm.contains(l)) return true;
        }
        return false;
    }

    /** "açaí" → "Açaí" — primeira letra maiúscula. */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private boolean ehTituloCategoria(String linha) {
        String norm = normalizar(linha);
        String[] gatilhos = {
                "entrada", "entradas", "petisco", "petiscos", "acompanhamento", "acompanhamentos",
                "bebida", "bebidas", "drink", "drinks", "sobremesa", "sobremesas",
                "lanche", "lanches", "hamburguer", "hamburgueres", "burger", "burgers",
                "pizza", "pizzas", "massa", "massas", "executivo", "executivos",
                "prato", "pratos", "porcao", "porcoes", "salada", "saladas",
                "menu", "cardapio", "promocao", "promocoes", "combo", "combos",
                "acai", "acais", "sorvete", "sorvetes", "doce", "doces",
                "salgado", "salgados", "pastel", "pasteis", "esfiha", "esfihas",
                "sushi", "japonesa", "japones", "wrap", "wraps", "tapioca", "tapiocas",
                "milkshake", "milkshakes", "shake", "shakes", "cafe", "cafes"
        };
        for (String g : gatilhos) {
            if (norm.equals(g) || norm.startsWith(g + " ") || norm.startsWith(g + "s ")
                    || norm.endsWith(" " + g) || norm.endsWith(" " + g + "s")) return true;
        }
        return false;
    }

    // ── CONFIRMAÇÃO (salvar) ─────────────────────────────────────────────────

    @Transactional
    public Map<String, Integer> confirmar(Long restauranteId, ImportacaoConfirmRequest req) {
        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        if (req.getCategorias() == null || req.getCategorias().isEmpty())
            throw new RuntimeException("Nenhuma categoria para importar");

        boolean substituir = "substituir".equalsIgnoreCase(req.getModo());

        if (substituir) {
            var categoriasAntigas = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(r.getId());
            for (Categoria c : categoriasAntigas) {
                var produtos = produtoRepository.findByCategoriaId(c.getId());
                for (var p : produtos) cardapioService.prepararProdutoParaExclusao(p);
                produtoRepository.deleteAll(produtos);
            }
            categoriaRepository.deleteAll(categoriasAntigas);
        }

        Map<String, Categoria> categoriasMap = new HashMap<>();
        var existentes = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(r.getId());
        for (Categoria c : existentes) categoriasMap.put(normalizar(c.getNome()), c);

        int totalCategorias = 0;
        int totalProdutos = 0;

        for (var cat : req.getCategorias()) {
            String chave = normalizar(cat.getNome());
            Categoria categoria = categoriasMap.get(chave);
            if (categoria == null) {
                categoria = new Categoria();
                categoria.setRestaurante(r);
                categoria.setNome(cat.getNome());
                categoria.setOrdem(existentes.size() + totalCategorias);
                categoria = categoriaRepository.save(categoria);
                categoriasMap.put(chave, categoria);
                totalCategorias++;
            }

            if (cat.getProdutos() == null) continue;
            for (var p : cat.getProdutos()) {
                if (Boolean.FALSE.equals(p.getImportar())) continue;
                if (p.getNome() == null || p.getNome().isBlank()) continue;
                if (p.getPreco() == null) continue;

                Produto produto = new Produto();
                produto.setCategoria(categoria);
                produto.setRestaurante(r);
                produto.setNome(p.getNome().trim());
                produto.setDescricao(p.getDescricao());
                produto.setPreco(p.getPreco());
                produto.setFotoUrl(p.getImagemUrl());
                produto.setDisponivel(true);
                produtoRepository.save(produto);
                totalProdutos++;
            }
        }

        log.info("📥 Cardápio importado: {} categorias novas, {} produtos no restaurante {}",
                totalCategorias, totalProdutos, r.getId());

        Map<String, Integer> resultado = new HashMap<>();
        resultado.put("categoriasNovas", totalCategorias);
        resultado.put("produtosImportados", totalProdutos);
        return resultado;
    }

    // ── PARSING CSV/XLSX ─────────────────────────────────────────────────────

    private List<List<String>> lerCsv(MultipartFile arquivo) throws IOException {
        byte[] bytes = arquivo.getBytes();
        String conteudo = new String(bytes, StandardCharsets.UTF_8);
        if (conteudo.contains("�")) {
            conteudo = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        if (conteudo.startsWith("﻿")) conteudo = conteudo.substring(1);

        String[] linhasRaw = conteudo.split("\\r?\\n");
        if (linhasRaw.length == 0) return new ArrayList<>();

        char delim = detectarDelimitador(linhasRaw[0]);

        List<List<String>> linhas = new ArrayList<>();
        for (String linha : linhasRaw) {
            if (linha.trim().isEmpty()) continue;
            linhas.add(parseLinhaCsv(linha, delim));
        }
        return linhas;
    }

    private char detectarDelimitador(String linha) {
        int virgulas = (int) linha.chars().filter(c -> c == ',').count();
        int pontoVirgulas = (int) linha.chars().filter(c -> c == ';').count();
        int tabs = (int) linha.chars().filter(c -> c == '\t').count();
        int pipes = (int) linha.chars().filter(c -> c == '|').count();
        int max = Math.max(Math.max(virgulas, pontoVirgulas), Math.max(tabs, pipes));
        if (max == 0) return ',';
        if (tabs == max) return '\t';
        if (pipes == max) return '|';
        if (pontoVirgulas == max) return ';';
        return ',';
    }

    private List<String> parseLinhaCsv(String linha, char delim) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (dentroAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"'); i++;
                } else {
                    dentroAspas = !dentroAspas;
                }
            } else if (c == delim && !dentroAspas) {
                campos.add(atual.toString().trim());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString().trim());
        return campos;
    }

    private List<List<String>> lerExcel(MultipartFile arquivo) throws IOException {
        List<List<String>> linhas = new ArrayList<>();
        try (InputStream is = arquivo.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<String> linha = new ArrayList<>();
                int ultColuna = row.getLastCellNum();
                for (int c = 0; c < ultColuna; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    linha.add(valorCell(cell));
                }
                if (linha.stream().allMatch(s -> s == null || s.isBlank())) continue;
                linhas.add(linha);
            }
        }
        return linhas;
    }

    private String valorCell(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
                    yield String.valueOf((long) v);
                }
                yield String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) {
                    try { yield String.valueOf(cell.getNumericCellValue()); }
                    catch (Exception ex) { yield ""; }
                }
            }
            default -> "";
        };
    }

    // ── BUILD DO PREVIEW ─────────────────────────────────────────────────────

    private ImportacaoPreviewDTO montarPreview(List<List<String>> linhas) {
        if (linhas.size() < 2)
            throw new RuntimeException("Arquivo precisa ter pelo menos cabeçalho + 1 linha de dados");

        List<String> headers = linhas.get(0);
        Map<String, Integer> mapping = mapearColunas(headers);
        List<String> avisos = new ArrayList<>();

        if (mapping.get("nome") == null)
            throw new RuntimeException(
                "Não encontrei a coluna do nome do produto. Renomeie pra 'Nome', 'Produto' " +
                "ou similar. Colunas detectadas: " + headers);
        if (mapping.get("preco") == null)
            throw new RuntimeException(
                "Não encontrei a coluna do preço. Renomeie pra 'Preço', 'Valor' ou similar. " +
                "Colunas detectadas: " + headers);

        // Aviso sobre matches aproximados (transparência)
        for (var e : mapping.entrySet()) {
            if (e.getValue() == null) continue;
            String header = headers.get(e.getValue());
            String esperado = e.getKey();
            String norm = normalizar(header);
            boolean exato = Arrays.stream(sinonimosDe(esperado)).anyMatch(s -> norm.equals(s) || norm.contains(s));
            if (!exato) {
                avisos.add("Coluna \"" + header + "\" interpretada como " + esperado + " (fuzzy match — confirme nos itens).");
            }
        }

        String plataforma = detectarPlataforma(headers);

        Map<String, List<ImportacaoPreviewDTO.ProdutoImport>> porCategoria = new LinkedHashMap<>();
        int invalidas = 0;

        for (int i = 1; i < linhas.size(); i++) {
            List<String> linha = linhas.get(i);
            String nome = pegar(linha, mapping.get("nome"));
            String precoStr = pegar(linha, mapping.get("preco"));

            if (nome == null || nome.isBlank()) { invalidas++; continue; }

            BigDecimal preco = parsearPreco(precoStr);
            if (preco == null) {
                invalidas++;
                avisos.add("Linha " + (i + 1) + ": preço inválido (\"" + precoStr + "\") — ignorada");
                continue;
            }

            String categoria = pegar(linha, mapping.get("categoria"));
            if (categoria == null || categoria.isBlank()) categoria = "Geral";

            String descricao = pegar(linha, mapping.get("descricao"));
            String imagem = pegar(linha, mapping.get("imagem"));

            porCategoria.computeIfAbsent(categoria, k -> new ArrayList<>())
                    .add(ImportacaoPreviewDTO.ProdutoImport.builder()
                            .nome(nome.trim())
                            .descricao(descricao)
                            .preco(preco)
                            .imagemUrl(imagem)
                            .importar(true)
                            .build());
        }

        List<ImportacaoPreviewDTO.CategoriaImport> categorias = new ArrayList<>();
        for (var e : porCategoria.entrySet()) {
            categorias.add(ImportacaoPreviewDTO.CategoriaImport.builder()
                    .nome(e.getKey())
                    .produtos(e.getValue())
                    .build());
        }

        if (invalidas > 0) {
            avisos.add(0, invalidas + " linha(s) foram ignoradas por falta de nome/preço válido.");
        }

        return ImportacaoPreviewDTO.builder()
                .plataformaDetectada(plataforma)
                .totalLinhas(linhas.size() - 1)
                .linhasInvalidas(invalidas)
                .avisos(avisos)
                .categorias(categorias)
                .build();
    }

    private String[] sinonimosDe(String tipo) {
        return switch (tipo) {
            case "nome" -> COL_NOME;
            case "descricao" -> COL_DESC;
            case "preco" -> COL_PRECO;
            case "categoria" -> COL_CATEGORIA;
            case "imagem" -> COL_IMAGEM;
            default -> new String[0];
        };
    }

    /**
     * Mapeia colunas com 3 níveis de fallback:
     *  1) match exato (igual a um sinônimo)
     *  2) contains (header inclui o sinônimo OU vice-versa)
     *  3) fuzzy (Levenshtein <= FUZZY_MAX_DIST)
     */
    private Map<String, Integer> mapearColunas(List<String> headers) {
        Map<String, Integer> map = new HashMap<>();
        String[] tipos = {"nome", "descricao", "preco", "categoria", "imagem"};
        for (String tipo : tipos) {
            map.put(tipo, buscarColuna(headers, sinonimosDe(tipo)));
        }
        return map;
    }

    private Integer buscarColuna(List<String> headers, String[] sinonimos) {
        // Nível 1+2: exato e contains
        for (int i = 0; i < headers.size(); i++) {
            String h = normalizar(headers.get(i));
            if (h.isBlank()) continue;
            for (String sin : sinonimos) {
                if (h.equals(sin)) return i;
            }
            for (String sin : sinonimos) {
                // Se header CONTÉM sinônimo OU sinônimo CONTÉM header (header curto tipo "preco")
                if (h.contains(sin) || (sin.length() >= 4 && sin.contains(h))) return i;
            }
        }
        // Nível 3: fuzzy
        int melhorIdx = -1;
        int melhorDist = FUZZY_MAX_DIST + 1;
        for (int i = 0; i < headers.size(); i++) {
            String h = normalizar(headers.get(i));
            if (h.isBlank() || h.length() < 3) continue;
            for (String sin : sinonimos) {
                if (Math.abs(h.length() - sin.length()) > FUZZY_MAX_DIST) continue;
                int d = levenshtein(h, sin);
                if (d < melhorDist) { melhorDist = d; melhorIdx = i; }
            }
        }
        return melhorIdx >= 0 ? melhorIdx : null;
    }

    /** Distância de edição (Levenshtein) clássica — usa só pra strings curtas. */
    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private String detectarPlataforma(List<String> headers) {
        String joined = headers.stream()
                .map(this::normalizar)
                .reduce("", (a, b) -> a + "|" + b);
        if (joined.contains("anotaai") || (joined.contains("complementos") && joined.contains("preco")))
            return "Anotaaí";
        if (joined.contains("olaclick") || (joined.contains("description") && joined.contains("category")))
            return "Olaclick";
        if (joined.contains("ifood")) return "iFood";
        if (joined.contains("goomer")) return "Goomer";
        if (joined.contains("99food") || joined.contains("99 food")) return "99Food";
        if (joined.contains("keeta")) return "Keeta";
        return "Genérico";
    }

    private BigDecimal parsearPreco(String s) {
        if (s == null) return null;
        // Limpa tudo que não é dígito, vírgula ou ponto
        String limpo = s.trim()
                .replaceAll("(?i)r\\$", "")
                .replaceAll("[^0-9.,]", "")
                .trim();
        if (limpo.isEmpty()) return null;

        int virgula = limpo.lastIndexOf(',');
        int ponto = limpo.lastIndexOf('.');
        try {
            if (virgula > ponto) {
                limpo = limpo.replace(".", "").replace(",", ".");
            } else if (ponto > virgula) {
                limpo = limpo.replace(",", "");
            }
            BigDecimal preco = new BigDecimal(limpo).setScale(2, RoundingMode.HALF_UP);
            // Sanidade: preço entre R$ 0,01 e R$ 100.000
            if (preco.compareTo(BigDecimal.ZERO) <= 0) return null;
            if (preco.compareTo(new BigDecimal("100000")) > 0) return null;
            return preco;
        } catch (Exception e) {
            return null;
        }
    }

    private String pegar(List<String> linha, Integer idx) {
        if (idx == null || idx < 0 || idx >= linha.size()) return null;
        String v = linha.get(idx);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private String normalizar(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("[_\\-./]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
