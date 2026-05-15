package com.mydelivery.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
 * Importador de cardápio via CSV/XLSX.
 *
 * Fluxo:
 *  1. Cliente envia arquivo → analisar() retorna preview editável
 *  2. Cliente revisa e confirma → confirmar() salva no banco
 *
 * Recursos:
 *  - Auto-detecção de plataforma de origem (Anotaaí, Olaclick, etc.) pelos headers
 *  - Auto-mapping heurístico de colunas em PT + EN
 *  - Parser tolerante a preços em formatos variados (R$ 10,50 / 10.50 / 1050)
 *  - Detecção automática de delimitador CSV (vírgula, ponto-e-vírgula, tab)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportacaoCardapioService {

    private final CategoriaRepository categoriaRepository;
    private final ProdutoRepository produtoRepository;
    private final RestauranteRepository restauranteRepository;
    private final CardapioService cardapioService; // pra reusar prepararProdutoParaExclusao

    // ── Sinônimos pra detecção automática de coluna ──────────────────────────
    // Cada array é "essa coluna pode se chamar qualquer um destes" (lowercased + sem acento)
    private static final String[] COL_NOME = {
        "nome", "name", "produto", "product", "item", "titulo", "title", "descricao do produto"
    };
    private static final String[] COL_DESC = {
        "descricao", "description", "detalhes", "details", "obs", "observacao", "ingredientes", "sobre"
    };
    private static final String[] COL_PRECO = {
        "preco", "price", "valor", "valor unitario", "preco unitario", "custo", "amount"
    };
    private static final String[] COL_CATEGORIA = {
        "categoria", "category", "grupo", "group", "secao", "section", "menu"
    };
    private static final String[] COL_IMAGEM = {
        "imagem", "image", "foto", "photo", "img", "imagem url", "url imagem", "image url", "picture"
    };

    // ── ANÁLISE (preview) ────────────────────────────────────────────────────

    /**
     * Lê o arquivo, detecta tudo, retorna preview pra cliente editar.
     * Não toca no banco.
     */
    public ImportacaoPreviewDTO analisar(MultipartFile arquivo) {
        if (arquivo == null || arquivo.isEmpty())
            throw new RuntimeException("Arquivo vazio");

        String filename = arquivo.getOriginalFilename() != null
                ? arquivo.getOriginalFilename().toLowerCase()
                : "";

        try {
            List<List<String>> linhas;
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                linhas = lerExcel(arquivo);
            } else if (filename.endsWith(".pdf")) {
                linhas = lerPdf(arquivo);
            } else if (filename.endsWith(".csv") || filename.endsWith(".txt")) {
                linhas = lerCsv(arquivo);
            } else {
                // Tenta CSV por default se a extensão não bater
                linhas = lerCsv(arquivo);
            }
            return montarPreview(linhas);
        } catch (Exception e) {
            log.error("Erro ao analisar arquivo de importação", e);
            throw new RuntimeException("Não foi possível ler o arquivo: " + e.getMessage());
        }
    }

    // ── LEITURA DE PDF ───────────────────────────────────────────────────────

    /**
     * Extrai cardápio de um PDF (texto) via heurística.
     *
     * Estratégia:
     *  1. Extrai texto puro do PDF com PDFBox
     *  2. Quebra em linhas
     *  3. Regex pra detectar linhas com preço (ex: "R$ 12,50", "12.50")
     *  4. Linhas com preço → vira produto. O nome é a parte antes do preço.
     *  5. Linhas SEM preço, antes de um bloco de produtos → categoria
     *  6. Retorna estrutura tabular [Categoria, Nome, Descrição, Preço] que o resto do fluxo já entende
     *
     * Limitações: funciona em PDFs com texto extraível. Não funciona em PDFs
     * escaneados (imagens) sem OCR. Layouts em múltiplas colunas podem confundir.
     */
    private List<List<String>> lerPdf(MultipartFile arquivo) throws IOException {
        String texto;
        try (PDDocument doc = Loader.loadPDF(arquivo.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Preserva quebras de linha do layout original quando possível
            stripper.setSortByPosition(true);
            texto = stripper.getText(doc);
        }

        if (texto == null || texto.isBlank())
            throw new RuntimeException("PDF parece estar vazio ou ser uma imagem escaneada (sem texto extraível).");

        // Regex de preço: R$ 12, R$ 12,50, R$ 12.50, 12,50 (com R$ opcional)
        java.util.regex.Pattern PRECO = java.util.regex.Pattern.compile(
                "(R\\$\\s*)?([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2})|(R\\$\\s*)([0-9]+)\\b"
        );

        List<List<String>> linhas = new ArrayList<>();
        // Header fixo — o resto do código já espera esse formato
        linhas.add(Arrays.asList("Categoria", "Nome", "Descrição", "Preço"));

        String categoriaAtual = "Geral";
        // Bufferiza pra suportar nome em uma linha + preço na próxima
        String linhaPendente = null;

        for (String rawLine : texto.split("\\r?\\n")) {
            String linha = rawLine.trim();
            if (linha.isEmpty()) { linhaPendente = null; continue; }

            java.util.regex.Matcher m = PRECO.matcher(linha);
            if (m.find()) {
                // Tem preço — é um produto
                String precoStr = m.group();
                String antes = linha.substring(0, m.start()).trim();
                String depois = linha.substring(m.end()).trim();

                // Limpa pontuação esquerda do preço (R$, ponto, traço, ...)
                antes = antes.replaceAll("[.\\-–·•\\s]+$", "").trim();

                String nome;
                String descricao = "";
                if (antes.length() >= 2) {
                    nome = antes;
                    if (!depois.isEmpty()) descricao = depois;
                } else if (linhaPendente != null && linhaPendente.length() >= 2) {
                    // Nome na linha de cima, preço na de baixo (layout comum em alguns cardápios)
                    nome = linhaPendente;
                    if (!depois.isEmpty()) descricao = depois;
                } else {
                    // Preço sem nome — ignora
                    linhaPendente = null;
                    continue;
                }

                // Nome muito longo? geralmente é descrição que veio antes do preço
                if (nome.length() > 80 && nome.contains(" - ")) {
                    int idx = nome.indexOf(" - ");
                    descricao = (descricao.isEmpty() ? "" : descricao + " — ") + nome.substring(idx + 3).trim();
                    nome = nome.substring(0, idx).trim();
                }

                linhas.add(Arrays.asList(categoriaAtual, nome, descricao, precoStr));
                linhaPendente = null;
            } else {
                // Sem preço — pode ser categoria OU nome de produto cujo preço está na próxima linha
                // Heurística: linha curta (até 50 chars) e com pelo menos 1 letra alfabética
                if (linha.length() <= 50 && linha.matches(".*[A-Za-zÀ-ÿ].*")) {
                    // Se for TODA em maiúsculas OU termina sem pontuação curta → provavelmente categoria
                    boolean maiusculas = linha.equals(linha.toUpperCase()) && linha.length() >= 3;
                    boolean ehCategoria = maiusculas || linha.endsWith(":") || ehTituloCategoria(linha);
                    if (ehCategoria) {
                        categoriaAtual = linha.replaceAll("[:]+$", "").trim();
                        linhaPendente = null;
                        continue;
                    }
                }
                // Senão, guarda como possível nome do produto cujo preço vem na próxima
                linhaPendente = linha;
            }
        }

        if (linhas.size() == 1) {
            throw new RuntimeException("Não consegui identificar nenhum produto no PDF. Verifique se ele contém texto (não é só imagem) e se os preços estão visíveis. Tente CSV/Excel ou nosso template.");
        }
        return linhas;
    }

    /** Heurística complementar: detecta títulos de categoria comuns em cardápios. */
    private boolean ehTituloCategoria(String linha) {
        String norm = normalizar(linha);
        String[] gatilhos = {
                "entrada", "entradas", "petisco", "petiscos", "acompanhamento", "acompanhamentos",
                "bebida", "bebidas", "drink", "drinks", "sobremesa", "sobremesas",
                "lanche", "lanches", "hamburguer", "hamburgueres", "burger", "burgers",
                "pizza", "pizzas", "massa", "massas", "executivo", "executivos",
                "prato", "pratos", "porção", "porcoes", "saladas", "salada",
                "menu", "cardapio", "promocao", "promocoes", "combo", "combos"
        };
        for (String g : gatilhos) {
            // Linha curta começando com a palavra
            if (norm.equals(g) || norm.startsWith(g + " ") || norm.startsWith(g + "s ")) return true;
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
            // Deleta produtos e categorias atuais do restaurante (limpa tudo)
            var categoriasAntigas = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(r.getId());
            for (Categoria c : categoriasAntigas) {
                var produtos = produtoRepository.findByCategoriaId(c.getId());
                // Mesmo cleanup robusto (ficha técnica + snapshot em pedidos antigos)
                for (var p : produtos) cardapioService.prepararProdutoParaExclusao(p);
                produtoRepository.deleteAll(produtos);
            }
            categoriaRepository.deleteAll(categoriasAntigas);
        }

        // Reusa categorias existentes (busca por nome) ou cria novas
        Map<String, Categoria> categoriasMap = new HashMap<>();
        var existentes = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(r.getId());
        for (Categoria c : existentes) {
            categoriasMap.put(normalizar(c.getNome()), c);
        }

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
                if (Boolean.FALSE.equals(p.getImportar())) continue; // cliente desmarcou
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

    // ── PARSING ──────────────────────────────────────────────────────────────

    /** Lê CSV detectando delimitador automaticamente (, ; \t). */
    private List<List<String>> lerCsv(MultipartFile arquivo) throws IOException {
        byte[] bytes = arquivo.getBytes();
        String conteudo = new String(bytes, StandardCharsets.UTF_8);
        // Se o UTF-8 deu problema (caracteres estranhos), tenta latin-1
        if (conteudo.contains("�")) {
            conteudo = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        // Remove BOM se houver
        if (conteudo.startsWith("﻿")) conteudo = conteudo.substring(1);

        String[] linhasRaw = conteudo.split("\\r?\\n");
        if (linhasRaw.length == 0) return new ArrayList<>();

        // Detecta delimitador: conta ocorrências de cada um na 1ª linha
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
        if (tabs >= virgulas && tabs >= pontoVirgulas) return '\t';
        if (pontoVirgulas > virgulas) return ';';
        return ',';
    }

    /** Parser CSV simples com suporte a aspas (campo entre " " pode conter delimitador). */
    private List<String> parseLinhaCsv(String linha, char delim) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                // "" dentro de campo aspeado = aspas literal
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

    /** Lê XLSX usando Apache POI. */
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
                // Linha completamente vazia → ignora
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
                // Inteiro? sem casas decimais inúteis
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

        if (mapping.get("nome") == null)
            throw new RuntimeException("Não encontrei coluna com nome de produto. Renomeie a coluna para 'Nome' ou 'Produto'.");
        if (mapping.get("preco") == null)
            throw new RuntimeException("Não encontrei coluna de preço. Renomeie para 'Preço' ou 'Valor'.");

        String plataforma = detectarPlataforma(headers);

        // Agrupa por categoria mantendo ordem de inserção
        Map<String, List<ImportacaoPreviewDTO.ProdutoImport>> porCategoria = new LinkedHashMap<>();
        List<String> avisos = new ArrayList<>();
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

    /**
     * Mapeia índice de cada coluna conhecida (nome, preco, etc.) usando heurísticas.
     * Retorna Map<tipo, indice> — valor null se não encontrou.
     */
    private Map<String, Integer> mapearColunas(List<String> headers) {
        Map<String, Integer> map = new HashMap<>();
        map.put("nome", buscarColuna(headers, COL_NOME));
        map.put("descricao", buscarColuna(headers, COL_DESC));
        map.put("preco", buscarColuna(headers, COL_PRECO));
        map.put("categoria", buscarColuna(headers, COL_CATEGORIA));
        map.put("imagem", buscarColuna(headers, COL_IMAGEM));
        return map;
    }

    private Integer buscarColuna(List<String> headers, String[] sinonimos) {
        for (int i = 0; i < headers.size(); i++) {
            String h = normalizar(headers.get(i));
            for (String sin : sinonimos) {
                if (h.equals(sin) || h.contains(sin)) return i;
            }
        }
        return null;
    }

    private String detectarPlataforma(List<String> headers) {
        String joined = headers.stream()
                .map(this::normalizar)
                .reduce("", (a, b) -> a + "|" + b);
        // Heurísticas — cada plataforma exporta com palavras-chave próprias
        if (joined.contains("anotaai") || (joined.contains("complementos") && joined.contains("preco")))
            return "Anotaaí";
        if (joined.contains("olaclick") || (joined.contains("description") && joined.contains("category")))
            return "Olaclick";
        if (joined.contains("ifood")) return "iFood";
        if (joined.contains("goomer")) return "Goomer";
        return "Genérico";
    }

    /**
     * Parser tolerante de preço:
     *  "R$ 10,50" → 10.50
     *  "10,50"    → 10.50
     *  "10.50"    → 10.50
     *  "1.250,00" → 1250.00 (formato pt-BR)
     *  "1,250.00" → 1250.00 (formato en-US)
     */
    private BigDecimal parsearPreco(String s) {
        if (s == null) return null;
        String limpo = s.trim().replaceAll("[Rr]\\$|\\s|[A-Za-z]", "");
        if (limpo.isEmpty()) return null;
        // Detecta formato pelo último separador (decimal)
        int virgula = limpo.lastIndexOf(',');
        int ponto = limpo.lastIndexOf('.');
        try {
            if (virgula > ponto) {
                // pt-BR: ponto é milhar, vírgula é decimal
                limpo = limpo.replace(".", "").replace(",", ".");
            } else if (ponto > virgula) {
                // en-US: vírgula é milhar, ponto é decimal
                limpo = limpo.replace(",", "");
            }
            // Se só tem 1 separador e está no fim, é decimal — já tratado acima
            return new BigDecimal(limpo).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private String pegar(List<String> linha, Integer idx) {
        if (idx == null || idx < 0 || idx >= linha.size()) return null;
        String v = linha.get(idx);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** Normaliza string: lowercase + sem acento + sem espaços nas pontas. */
    private String normalizar(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .trim();
    }
}
