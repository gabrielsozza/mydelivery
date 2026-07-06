#!/usr/bin/env python3
with open("logo-b64.txt") as f:
    # remove line-number/prefix artifacts if any, keep only base64 chars
    raw = f.read()
b64 = "".join(raw.split())
# strip potential leading index like "1" if base64 does not start with i
if b64.startswith("1iVBOR"):
    b64 = b64[1:]
logo = f"data:image/png;base64,{b64}"

html = f"""<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<style>
  :root {{
    --navy:#0e1c30;
    --navy2:#14273f;
    --orange:#ef6a2b;
    --orange-soft:#fbe6da;
    --gray:#5b6b7c;
    --line:#e3e8ee;
    --ink:#1c2b3a;
  }}
  @page {{ size:A4; margin:0; }}
  * {{ box-sizing:border-box; margin:0; padding:0; }}
  body {{
    font-family:-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    color:var(--ink);
    font-size:11.5px;
    line-height:1.6;
    -webkit-print-color-adjust:exact;
    print-color-adjust:exact;
  }}
  .page {{
    width:210mm;
    height:297mm;
    padding:16mm 18mm 16mm 18mm;
    background:#fff;
    position:relative;
    overflow:hidden;
    page-break-after:always;
  }}
  .page:last-child {{ page-break-after:auto; }}

  /* ---------- CAPA ---------- */
  .cover {{
    background:linear-gradient(160deg,#0e1c30 0%,#14273f 60%,#1b3busca 100%);
    background:linear-gradient(160deg,#0e1c30 0%,#14273f 55%,#193150 100%);
    color:#fff;
    display:flex;
    flex-direction:column;
    justify-content:space-between;
    padding:26mm 20mm;
  }}
  .cover .brand {{ display:flex; align-items:center; gap:16px; }}
  .cover .brand img {{ width:120px; height:auto; border-radius:10px; }}
  .cover .brand .cnpj {{ font-size:10px; color:#9fb2c6; letter-spacing:.5px; }}
  .cover .center {{ margin:auto 0; }}
  .cover .kicker {{
    color:var(--orange); font-weight:700; letter-spacing:3px;
    font-size:12px; text-transform:uppercase; margin-bottom:14px;
  }}
  .cover h1 {{ font-size:38px; line-height:1.15; font-weight:800; margin-bottom:18px; }}
  .cover h1 span {{ color:var(--orange); }}
  .cover .sub {{ font-size:14px; color:#c6d2df; max-width:135mm; }}
  .cover .foot {{ display:flex; justify-content:space-between; align-items:flex-end;
    border-top:1px solid rgba(255,255,255,.15); padding-top:14px; font-size:10.5px; color:#9fb2c6; }}
  .cover .foot b {{ color:#fff; }}
  .cover .tag {{
    display:inline-block; background:var(--orange); color:#fff; font-weight:700;
    padding:6px 14px; border-radius:30px; font-size:11px; margin-top:20px;
  }}

  /* ---------- CONTEUDO ---------- */
  .topbar {{
    display:flex; justify-content:space-between; align-items:center;
    border-bottom:2px solid var(--line); padding-bottom:10px; margin-bottom:18px;
  }}
  .topbar .lbl {{ display:flex; align-items:center; gap:10px; font-weight:700; color:var(--navy); font-size:12px; }}
  .topbar .lbl img {{ width:40px; border-radius:6px; }}
  .topbar .pg {{ font-size:10px; color:var(--gray); }}

  h2 {{
    font-size:17px; color:var(--navy); font-weight:800; margin:0 0 4px 0;
    display:flex; align-items:center; gap:10px;
  }}
  h2 .num {{
    background:var(--orange); color:#fff; width:26px; height:26px; border-radius:7px;
    display:inline-flex; align-items:center; justify-content:center; font-size:13px; flex:0 0 auto;
  }}
  .sec-desc {{ color:var(--gray); margin:0 0 12px 46px; font-size:11px; }}
  section {{ margin-bottom:18px; }}
  p {{ margin-bottom:9px; }}
  .lead {{ font-size:12px; }}

  .card {{
    border:1px solid var(--line); border-radius:11px; padding:16px 18px; margin-bottom:12px;
    background:#fbfcfd;
  }}
  .card h3 {{ font-size:12.5px; color:var(--navy); margin-bottom:8px; display:flex; align-items:center; gap:8px; }}
  .card h3::before {{ content:""; width:9px; height:9px; background:var(--orange); border-radius:50%; }}
  ul {{ margin:0 0 0 4px; list-style:none; }}
  ul li {{ position:relative; padding-left:20px; margin-bottom:6px; }}
  ul li::before {{
    content:"\\2713"; position:absolute; left:0; top:0; color:var(--orange); font-weight:800;
  }}
  .two {{ display:flex; gap:14px; }}
  .two > div {{ flex:1; }}

  table {{ width:100%; border-collapse:collapse; margin-top:6px; font-size:11px; }}
  th, td {{ text-align:left; padding:11px 14px; border-bottom:1px solid var(--line); }}
  th {{ background:var(--navy); color:#fff; font-weight:600; }}
  th:last-child, td:last-child {{ text-align:right; }}
  tr:nth-child(even) td {{ background:#f7f9fb; }}
  .tot td {{ font-weight:800; color:var(--navy); font-size:13px; border-top:2px solid var(--navy); background:#fff!important; }}

  .tech {{ display:flex; gap:12px; }}
  .tech .box {{
    flex:1; border:1px solid var(--line); border-radius:11px; padding:14px; text-align:center; background:#fbfcfd;
  }}
  .tech .box .k {{ font-size:9.5px; letter-spacing:1.5px; text-transform:uppercase; color:var(--orange); font-weight:700; margin-bottom:6px; }}
  .tech .box .v {{ font-size:12.5px; font-weight:700; color:var(--navy); }}
  .tech .box .d {{ font-size:10px; color:var(--gray); margin-top:4px; }}

  .callout {{
    border-left:4px solid var(--orange); background:var(--orange-soft);
    padding:14px 16px; border-radius:0 10px 10px 0; margin:12px 0;
  }}
  .callout b {{ color:#b34715; }}

  .opt {{ display:flex; gap:14px; margin-top:12px; }}
  .opt > div {{ flex:1; border:1px solid var(--line); border-radius:11px; overflow:hidden; }}
  .opt .hd {{ background:var(--navy); color:#fff; padding:10px 14px; font-weight:700; font-size:12px; }}
  .opt .hd.alt {{ background:var(--orange); }}
  .opt .bd {{ padding:14px; font-size:10.8px; color:var(--ink); }}

  .timeline {{ margin-top:8px; }}
  .timeline .step {{ display:flex; gap:14px; margin-bottom:8px; }}
  .timeline .dot {{
    flex:0 0 auto; width:30px; height:30px; border-radius:50%; background:var(--navy); color:#fff;
    display:flex; align-items:center; justify-content:center; font-weight:700; font-size:11px;
  }}
  .timeline .step:nth-child(odd) .dot {{ background:var(--orange); }}
  .timeline .txt b {{ color:var(--navy); font-size:12px; }}
  .timeline .txt div {{ font-size:10.5px; color:var(--gray); }}

  .pay {{ display:flex; gap:14px; margin-top:10px; }}
  .pay > div {{ flex:1; border:2px solid var(--line); border-radius:12px; padding:16px; text-align:center; }}
  .pay > div.hl {{ border-color:var(--orange); }}
  .pay .k {{ font-size:10px; text-transform:uppercase; letter-spacing:1px; color:var(--gray); font-weight:700; }}
  .pay .val {{ font-size:26px; font-weight:800; color:var(--navy); margin:4px 0; }}
  .pay .val small {{ font-size:13px; }}
  .pay .d {{ font-size:10px; color:var(--gray); }}

  .footer {{
    position:absolute; bottom:12mm; left:18mm; right:18mm;
    border-top:1px solid var(--line); padding-top:8px;
    display:flex; justify-content:space-between; font-size:9px; color:#9aa7b4;
  }}
  .big-num {{ font-size:14px; }}
</style>
</head>
<body>

<!-- ============ CAPA ============ -->
<div class="page cover">
  <div class="brand">
    <img src="{logo}" alt="Studio H5">
    <div>
      <div style="font-weight:800;font-size:15px;letter-spacing:1px;">STUDIO H5</div>
      <div class="cnpj">CNPJ 51.965.304/0001-24</div>
    </div>
  </div>

  <div class="center">
    <div class="kicker">Proposta Comercial</div>
    <h1>Sistema de <span>SAC &amp; Ouvidoria</span><br>para a Vidroforte</h1>
    <div class="sub">
      Plataforma completa para registro, acompanhamento e tratativa de reclamações
      dos clientes que adquiriram produtos da Vidroforte — um portal público de ouvidoria
      integrado a um painel interno de atendimento. Inspirado no modelo do Reclame Aqui,
      porém sob medida, mais organizado e sob total controle da empresa.
    </div>
    <div class="tag">Desenvolvimento sob medida &nbsp;•&nbsp; Web</div>
  </div>

  <div class="foot">
    <div>Elaborado por <b>Studio H5</b></div>
    <div>Emissão: <b>02/07/2026</b> &nbsp;|&nbsp; Validade: <b>15 dias</b></div>
  </div>
</div>

<!-- ============ PAGINA 2 - VISAO GERAL ============ -->
<div class="page">
  <div class="topbar">
    <div class="lbl"><img src="{logo}"> Proposta — SAC &amp; Ouvidoria Vidroforte</div>
    <div class="pg">Studio H5</div>
  </div>

  <section>
    <h2><span class="num">1</span> Entendimento do projeto</h2>
    <p class="sec-desc">O que vamos construir e para quem.</p>
    <p class="lead">
      A Vidroforte precisa de um canal profissional de <b>ouvidoria</b> onde os clientes que
      compraram seus produtos possam registrar reclamações de forma simples, anexando fotos,
      nota fiscal e documentos. Do outro lado, o setor de <b>SAC</b> precisa de um painel interno
      robusto para receber, tratar, encaminhar e responder cada caso, com total rastreabilidade
      de quem fez o quê.
    </p>
    <p>
      A proposta contempla, portanto, <b>dois ambientes integrados</b>: o portal público do cliente
      e o sistema interno de gestão do atendimento. Ambos compartilham a mesma base de dados e
      seguem uma jornada única do protocolo — da abertura ao encerramento.
    </p>

    <div class="callout">
      <b>A ideia central:</b> algo parecido com o Reclame Aqui, só que melhor — privado da Vidroforte,
      sem exposição pública desnecessária, com fluxo interno de tratativa, histórico completo e a
      identidade da empresa.
    </div>
  </section>

  <section>
    <h2><span class="num">2</span> Portal do Cliente — Ouvidoria</h2>
    <p class="sec-desc">Ambiente público onde o consumidor abre e acompanha sua reclamação.</p>

    <div class="two">
      <div class="card">
        <h3>Abertura da reclamação</h3>
        <ul>
          <li><b>Título</b> da reclamação</li>
          <li><b>Descrição</b> detalhada do problema</li>
          <li><b>Onde comprou</b> (loja / revenda / origem)</li>
          <li><b>Qual vidro / produto</b> adquirido</li>
          <li>Anexo de <b>foto(s)</b> do produto</li>
          <li>Anexo da <b>nota fiscal</b> (imagem ou PDF)</li>
          <li>Anexo de <b>arquivos e PDFs</b> complementares</li>
        </ul>
      </div>
      <div class="card">
        <h3>Acompanhamento</h3>
        <ul>
          <li>Geração de <b>número de protocolo</b></li>
          <li>Consulta do <b>status</b> a qualquer momento</li>
          <li>Visualização das <b>respostas</b> do SAC</li>
          <li>Possibilidade de <b>complementar</b> o caso</li>
          <li>Notificação por e-mail a cada atualização</li>
          <li>Layout responsivo (celular e computador)</li>
          <li>Visual com a <b>identidade da Vidroforte</b></li>
        </ul>
      </div>
    </div>
  </section>

  <div class="footer">
    <div>Studio H5 — CNPJ 51.965.304/0001-24</div>
    <div>Proposta SAC &amp; Ouvidoria Vidroforte</div>
  </div>
</div>

<!-- ============ PAGINA 3 - PAINEL INTERNO ============ -->
<div class="page">
  <div class="topbar">
    <div class="lbl"><img src="{logo}"> Proposta — SAC &amp; Ouvidoria Vidroforte</div>
    <div class="pg">Studio H5</div>
  </div>

  <section>
    <h2><span class="num">3</span> Sistema Interno — Painel do SAC</h2>
    <p class="sec-desc">Onde a equipe da Vidroforte trata cada reclamação de ponta a ponta.</p>

    <div class="two">
      <div class="card">
        <h3>Gestão e tratativa</h3>
        <ul>
          <li>Acesso protegido por <b>login e senha</b></li>
          <li>Lista de reclamações com <b>filtros e busca</b> (status, data, produto, loja)</li>
          <li>Abertura do caso com <b>todos os anexos</b> do cliente</li>
          <li><b>Responder</b> o cliente diretamente pelo painel</li>
          <li>Alterar o <b>status</b> (aberto, em análise, respondido, encerrado)</li>
          <li><b>Encaminhar</b> o caso para outra pessoa / setor tratar</li>
          <li>Notas internas visíveis só para a equipe</li>
        </ul>
      </div>
      <div class="card">
        <h3>Controle e rastreabilidade</h3>
        <ul>
          <li>Registro do <b>nome do usuário que tratou</b> cada etapa</li>
          <li><b>Histórico completo</b> de tudo que aconteceu no protocolo</li>
          <li>Data e hora de cada movimentação</li>
          <li>Múltiplos usuários / atendentes</li>
          <li>Perfis de acesso (atendente / administrador)</li>
          <li>Painel-resumo com <b>indicadores</b> (abertos, pendentes, resolvidos)</li>
        </ul>
      </div>
    </div>
  </section>

  <section>
    <h2><span class="num">4</span> Base de usuários — flexibilidade</h2>
    <p class="sec-desc">Como os acessos à ouvidoria se relacionam com a base de clientes existente.</p>
    <p>
      Sobre os usuários que terão acesso à aba de <b>SAC / Ouvidoria</b> para abrir uma reclamação,
      há duas possibilidades — a escolha é do solicitante:
    </p>
    <div class="opt">
      <div>
        <div class="hd">Opção A — Base unificada</div>
        <div class="bd">
          Se o público for o mesmo, podemos <b>unir os usuários da ouvidoria à base de dados dos
          clientes do catálogo</b> já existente. O cliente usa um único cadastro para tudo, evitando
          duplicidade e aproveitando as informações já registradas.
        </div>
      </div>
      <div>
        <div class="hd alt">Opção B — Base separada</div>
        <div class="bd">
          Caso seja um <b>público totalmente diferente</b>, criamos um <b>banco separado e independente</b>,
          que <b>não se une</b> ao do catálogo. Os dois ambientes ficam isolados, cada um com sua própria
          base de usuários.
        </div>
      </div>
    </div>
  </section>

  <div class="footer">
    <div>Studio H5 — CNPJ 51.965.304/0001-24</div>
    <div>Proposta SAC &amp; Ouvidoria Vidroforte</div>
  </div>
</div>

<!-- ============ PAGINA 4 - TECNOLOGIA + PRAZO + INVESTIMENTO ============ -->
<div class="page">
  <div class="topbar">
    <div class="lbl"><img src="{logo}"> Proposta — SAC &amp; Ouvidoria Vidroforte</div>
    <div class="pg">Studio H5</div>
  </div>

  <section>
    <h2><span class="num">5</span> Tecnologias utilizadas</h2>
    <p class="sec-desc">Stack robusta, madura e de baixo custo de manutenção.</p>
    <div class="tech">
      <div class="box">
        <div class="k">Front-end</div>
        <div class="v">HTML, CSS e JS puro</div>
        <div class="d">Interface leve e rápida</div>
      </div>
      <div class="box">
        <div class="k">Back-end</div>
        <div class="v">Java · Spring Boot</div>
        <div class="d">Segurança e escalabilidade</div>
      </div>
      <div class="box">
        <div class="k">Banco de dados</div>
        <div class="v">MySQL</div>
        <div class="d">Hospedado na Locaweb</div>
      </div>
      <div class="box">
        <div class="k">Arquivos</div>
        <div class="v">Cloudinary</div>
        <div class="d">Fotos, notas e PDFs</div>
      </div>
    </div>
    <p style="margin-top:12px;">
      O sistema roda com back-end em <b>Java Spring Boot</b> e front-end em <b>HTML, CSS e JavaScript puro</b>,
      com os dados armazenados em <b>MySQL hospedado no servidor da Locaweb</b>. Todos os anexos enviados pelos
      clientes (fotos, notas fiscais e PDFs) ficam guardados de forma segura no <b>Cloudinary</b>, mantendo o
      banco leve e o acesso aos arquivos rápido e confiável.
    </p>
  </section>

  <section>
    <h2><span class="num">6</span> Prazo de entrega</h2>
    <p class="sec-desc">Etapas previstas até a conclusão do projeto.</p>
    <div class="timeline">
      <div class="step"><div class="dot">1</div><div class="txt"><b>Alinhamento e estrutura</b><div>Definições finais, modelagem do banco e ambiente</div></div></div>
      <div class="step"><div class="dot">2</div><div class="txt"><b>Portal do cliente</b><div>Abertura de reclamações, anexos e acompanhamento</div></div></div>
      <div class="step"><div class="dot">3</div><div class="txt"><b>Painel interno do SAC</b><div>Tratativa, respostas, encaminhamento e histórico</div></div></div>
      <div class="step"><div class="dot">4</div><div class="txt"><b>Testes, ajustes e publicação</b><div>Homologação e entrega em produção</div></div></div>
    </div>
    <div class="callout" style="margin-top:6px;">
      <b>Prazo de conclusão:</b> até <b class="big-num">25 dias corridos</b>, contados a partir da confirmação
      da proposta e do pagamento da entrada.
    </div>
  </section>

  <section>
    <h2><span class="num">7</span> Investimento</h2>
    <p class="sec-desc">Valor total do desenvolvimento e forma de pagamento.</p>
    <table>
      <tr><th>Descrição</th><th>Valor</th></tr>
      <tr><td>Desenvolvimento completo do Sistema de SAC &amp; Ouvidoria<br><span style="color:#5b6b7c;font-size:10px;">Portal do cliente + painel interno + integrações (MySQL/Locaweb + Cloudinary)</span></td><td>R$ 5.000,00</td></tr>
      <tr class="tot"><td>Total do projeto</td><td>R$ 5.000,00</td></tr>
    </table>

    <div class="pay">
      <div class="hl">
        <div class="k">Entrada</div>
        <div class="val">R$ 2.500<small>,00</small></div>
        <div class="d">Na confirmação da proposta</div>
      </div>
      <div class="hl">
        <div class="k">Entrega</div>
        <div class="val">R$ 2.500<small>,00</small></div>
        <div class="d">Na entrega do projeto</div>
      </div>
    </div>
    <p style="margin-top:12px;text-align:center;color:#5b6b7c;">
      Investimento total de <b>R$ 5.000,00</b>, sendo <b>R$ 2.500,00 de entrada</b> e
      <b>R$ 2.500,00 na entrega</b> do projeto.
    </p>
  </section>
</div>

</body>
</html>"""

with open("proposta.html", "w", encoding="utf-8") as f:
    f.write(html)
print("proposta.html gerada")
