#!/usr/bin/env python3
with open("logo-b64.txt") as f:
    raw = f.read()
b64 = "".join(raw.split())
if b64.startswith("1iVBOR"):
    b64 = b64[1:]
logo = f"data:image/png;base64,{b64}"

html = f"""<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<style>
  @page {{ size:A4; margin:18mm 20mm; }}
  * {{ box-sizing:border-box; margin:0; padding:0; }}
  body {{
    font-family:"Times New Roman", Georgia, serif;
    color:#0f1a26;
    font-size:11.5pt;
    line-height:1.55;
    text-align:justify;
    -webkit-print-color-adjust:exact;
    print-color-adjust:exact;
  }}
  header {{
    display:flex; align-items:center; justify-content:space-between;
    border-bottom:2px solid #0e1c30; padding-bottom:10px; margin-bottom:22px;
  }}
  header .brand {{ display:flex; align-items:center; gap:12px; }}
  header .brand img {{ width:48px; height:48px; border-radius:6px; }}
  header .brand .n {{ font-family:Helvetica,Arial,sans-serif; font-weight:800; font-size:12pt; color:#0e1c30; letter-spacing:1px; }}
  header .brand .c {{ font-family:Helvetica,Arial,sans-serif; font-size:8.5pt; color:#5b6b7c; }}
  header .ref {{ font-family:Helvetica,Arial,sans-serif; font-size:8.5pt; color:#5b6b7c; text-align:right; line-height:1.4; }}
  header .ref b {{ color:#0e1c30; }}

  h1 {{
    font-family:Helvetica,Arial,sans-serif; text-align:center;
    font-size:14pt; color:#0e1c30; text-transform:uppercase;
    letter-spacing:1px; margin-bottom:4px;
  }}
  .subtitle {{
    text-align:center; font-family:Helvetica,Arial,sans-serif;
    font-size:9.5pt; color:#5b6b7c; margin-bottom:22px;
  }}

  p {{ margin-bottom:10px; text-indent:0; }}
  .qual p {{ margin-bottom:8px; }}
  .qual b {{ color:#0e1c30; }}
  .preambulo {{ margin:12px 0 20px; font-style:italic; }}

  h2 {{
    font-family:Helvetica,Arial,sans-serif;
    font-size:11pt; color:#0e1c30; text-transform:uppercase;
    margin:16px 0 8px; letter-spacing:.5px;
  }}
  ol.abc {{ margin:4px 0 8px 22px; }}
  ol.abc li {{ margin-bottom:4px; }}
  .item {{ margin-bottom:8px; }}
  .item .n {{ font-weight:700; color:#0e1c30; }}

  .sign {{ margin-top:34px; }}
  .sign .place {{ margin-bottom:36px; }}
  .sign .row {{ display:flex; justify-content:space-between; gap:40px; margin-bottom:26px; }}
  .sign .box {{ flex:1; text-align:center; }}
  .sign .line {{ border-top:1px solid #0e1c30; padding-top:6px; font-size:10pt; }}
  .sign .box b {{ display:block; font-family:Helvetica,Arial,sans-serif; font-size:10pt; }}
  .sign .box span {{ font-size:9pt; color:#5b6b7c; }}

  .test {{ margin-top:24px; font-size:10pt; }}
  .test .t {{ margin-top:14px; border-top:1px solid #0e1c30; padding-top:4px; display:flex; justify-content:space-between; }}
</style>
</head>
<body>

<header>
  <div class="brand">
    <img src="{logo}" alt="Studio H5">
    <div>
      <div class="n">STUDIO H5</div>
      <div class="c">CNPJ 51.965.304/0001-24</div>
    </div>
  </div>
  <div class="ref">
    <b>Contrato de Prestação de Serviços</b><br>
    Ref. Pedido de Compra nº <b>2041583</b><br>
    Emissão: 10/07/2026
  </div>
</header>

<h1>Contrato de Prestação de Serviços de Desenvolvimento de Software</h1>
<div class="subtitle">Sistema de SAC &amp; Ouvidoria — Vidroforte</div>

<div class="qual">
  <p><b>CONTRATANTE:</b> <b>VIDROFORTE INDÚSTRIA E COMÉRCIO DE VIDROS LTDA.</b>, pessoa jurídica de direito privado, inscrita no CNPJ/MF sob o nº <b>92.639.954/0002-48</b>, Inscrição Estadual nº <b>029/0355125</b>, com sede na Rodovia RS 122, nº 4.545, KM 69,5, Caxias do Sul, Estado do Rio Grande do Sul, CEP 95110-690, neste ato representada na forma de seu contrato social, doravante denominada simplesmente <b>CONTRATANTE</b>.</p>

  <p><b>CONTRATADA:</b> <b>GABRIEL PEREIRA DE SOUZA</b>, atuando sob o nome fantasia <b>STUDIO H5</b>, inscrito no CNPJ/MF sob o nº <b>51.965.304/0001-24</b>, com endereço na Rua Rio Jucu, nº 220, Município de Serra, Estado do Espírito Santo, CEP 29169-220, telefone (27) 99783-1582, doravante denominado simplesmente <b>CONTRATADA</b>.</p>
</div>

<p class="preambulo">
As partes acima qualificadas, doravante em conjunto denominadas “Partes”, têm entre si justo e contratado o presente <b>Contrato de Prestação de Serviços de Desenvolvimento de Software</b> (“Contrato”), que se regerá pelas cláusulas e condições a seguir estabelecidas, em conformidade com o Pedido de Compra nº 2041583, emitido pela CONTRATANTE em 10 de julho de 2026, e com a proposta comercial previamente aprovada.
</p>

<h2>Cláusula 1ª — Do Objeto</h2>
<div class="item"><span class="n">1.1.</span> Constitui objeto do presente Contrato a prestação, pela CONTRATADA à CONTRATANTE, dos serviços de <b>desenvolvimento sob medida de um Sistema de SAC &amp; Ouvidoria</b>, composto por dois módulos integrados:</div>
<ol class="abc" type="a">
  <li><b>Portal Público do Cliente:</b> ambiente destinado ao registro de reclamações pelos clientes da CONTRATANTE, contemplando os campos de título, descrição, local de compra, produto/vidro adquirido, anexo de fotografias, anexo de nota fiscal e anexo de arquivos e documentos em formato PDF, com geração de número de protocolo e funcionalidade de acompanhamento do status do atendimento.</li>
  <li><b>Sistema Interno de Gestão (Painel do SAC):</b> ambiente restrito à equipe da CONTRATANTE, protegido por autenticação, contemplando funcionalidades de resposta ao cliente, alteração de status, encaminhamento entre usuários/setores, registro do responsável por cada tratativa, histórico completo com data e hora, notas internas e perfis de acesso diferenciados (atendente e administrador).</li>
</ol>
<div class="item"><span class="n">1.2.</span> A definição sobre a integração da base de usuários da Ouvidoria à base de clientes do catálogo já existente na CONTRATANTE, ou, alternativamente, a criação de base de dados independente e apartada, será formalizada pela CONTRATANTE durante a fase de alinhamento inicial do projeto, e será executada pela CONTRATADA conforme opção escolhida.</div>

<h2>Cláusula 2ª — Das Especificações Técnicas</h2>
<div class="item"><span class="n">2.1.</span> O sistema será desenvolvido com a utilização das seguintes tecnologias: <b>(a)</b> front-end em HTML, CSS e JavaScript puro; <b>(b)</b> back-end em linguagem Java, com o framework Spring Boot; <b>(c)</b> banco de dados MySQL, hospedado em servidor da Locaweb; e <b>(d)</b> armazenamento de arquivos e mídias — fotos, notas fiscais e documentos em PDF — por intermédio do serviço Cloudinary.</div>
<div class="item"><span class="n">2.2.</span> As credenciais e o custeio dos serviços de hospedagem (Locaweb) e de armazenamento (Cloudinary) são de responsabilidade da CONTRATANTE, que os fornecerá tempestivamente à CONTRATADA, permitindo a publicação do sistema em ambiente de produção.</div>

<h2>Cláusula 3ª — Do Prazo</h2>
<div class="item"><span class="n">3.1.</span> O prazo total para execução, conclusão e entrega dos serviços é de até <b>25 (vinte e cinco) dias corridos</b>, com <b>data-limite de conclusão em 10 de agosto de 2026</b>.</div>
<div class="item"><span class="n">3.2.</span> Considera-se entrega, para todos os fins deste Contrato, a disponibilização, pela CONTRATADA, do sistema publicado e plenamente operacional no ambiente definido, seguida de apresentação e homologação pela CONTRATANTE.</div>

<h2>Cláusula 4ª — Do Valor e da Forma de Pagamento</h2>
<div class="item"><span class="n">4.1.</span> Pela integral prestação dos serviços descritos na Cláusula 1ª, a CONTRATANTE pagará à CONTRATADA o valor total, certo e ajustado de <b>R$ 5.000,00 (cinco mil reais)</b>.</div>
<div class="item"><span class="n">4.2.</span> O pagamento observará a seguinte forma:</div>
<ol class="abc" type="a">
  <li><b>1ª parcela — R$ 2.500,00 (dois mil e quinhentos reais)</b>, com previsão em <b>15/07/2026</b>, na confirmação e início dos serviços;</li>
  <li><b>2ª parcela — R$ 2.500,00 (dois mil e quinhentos reais)</b>, com previsão em <b>10/08/2026</b>, na entrega e homologação do sistema.</li>
</ol>
<div class="item"><span class="n">4.3.</span> Os pagamentos serão realizados por transferência bancária ou PIX, para os dados informados pela CONTRATADA, mediante emissão de nota fiscal de prestação de serviço, na qual deverá obrigatoriamente constar a referência ao Pedido de Compra nº 2041583.</div>

<h2>Cláusula 5ª — Das Obrigações da CONTRATADA</h2>
<div class="item"><span class="n">5.1.</span> São obrigações da CONTRATADA, sem prejuízo das demais previstas neste instrumento:</div>
<ol class="abc" type="a">
  <li>executar os serviços com zelo, técnica e diligência, observando o escopo, o cronograma e as especificações pactuadas;</li>
  <li>emitir a nota fiscal de prestação de serviços vinculada ao Pedido de Compra da CONTRATANTE;</li>
  <li>manter sigilo absoluto sobre todas as informações da CONTRATANTE às quais tiver acesso em razão da execução deste Contrato;</li>
  <li>realizar, sem custo adicional, as correções e ajustes de defeitos identificados durante a fase de homologação, dentro do escopo originalmente contratado;</li>
  <li>prestar garantia técnica dos serviços entregues, conforme cláusula específica;</li>
  <li>utilizar tecnologias, bibliotecas e componentes legalmente licenciados, respondendo por eventual violação de direitos de terceiros deles decorrentes.</li>
</ol>

<h2>Cláusula 6ª — Das Obrigações da CONTRATANTE</h2>
<div class="item"><span class="n">6.1.</span> São obrigações da CONTRATANTE:</div>
<ol class="abc" type="a">
  <li>efetuar os pagamentos nas datas e condições ajustadas na Cláusula 4ª;</li>
  <li>fornecer, tempestivamente, informações, conteúdos, identidade visual, credenciais de hospedagem/armazenamento e demais insumos necessários à execução dos serviços;</li>
  <li>designar interlocutor responsável pelo acompanhamento do projeto e pelas aprovações intermediárias;</li>
  <li>homologar ou apontar ajustes ao sistema em prazo razoável, entendido como até 5 (cinco) dias úteis contados da comunicação de entrega, sob pena de considerar-se aceito tacitamente o serviço prestado.</li>
</ol>

<h2>Cláusula 7ª — Da Propriedade Intelectual</h2>
<div class="item"><span class="n">7.1.</span> Concluída a entrega e efetuado o pagamento integral do valor pactuado, a CONTRATADA <b>cede e transfere à CONTRATANTE</b>, em caráter <b>definitivo, exclusivo e irrevogável</b>, todos os direitos patrimoniais sobre o código-fonte, layouts, estrutura de banco de dados e demais artefatos produzidos especificamente em razão deste Contrato, podendo a CONTRATANTE utilizá-los livremente para fins de uso, reprodução, adaptação, evolução, manutenção e distribuição.</div>
<div class="item"><span class="n">7.2.</span> A cessão referida no item anterior não abrange bibliotecas, frameworks e componentes de terceiros de licenciamento próprio (open source ou proprietários), os quais permanecem regidos por suas respectivas licenças.</div>
<div class="item"><span class="n">7.3.</span> Fica autorizada à CONTRATADA a mera menção do projeto em seu portfólio profissional, resguardada a confidencialidade dos dados e das informações internas da CONTRATANTE.</div>

<h2>Cláusula 8ª — Da Confidencialidade e da Proteção de Dados</h2>
<div class="item"><span class="n">8.1.</span> Todas as informações comerciais, técnicas, cadastrais, financeiras e de negócio da CONTRATANTE, incluindo, sem limitação, sua base de clientes, o conteúdo das reclamações e os documentos anexados pelos consumidores, serão tratadas em regime de <b>estrita confidencialidade</b>, durante e após a vigência deste Contrato.</div>
<div class="item"><span class="n">8.2.</span> Em razão do tratamento de dados pessoais no âmbito do sistema desenvolvido, ambas as Partes comprometem-se a observar integralmente a <b>Lei nº 13.709/2018 (Lei Geral de Proteção de Dados Pessoais — LGPD)</b>, atuando a CONTRATANTE na qualidade de <b>controladora</b> e a CONTRATADA na qualidade de <b>operadora</b>, no que couber, restringindo-se o uso dos dados às finalidades estritamente necessárias à execução do objeto contratado.</div>
<div class="item"><span class="n">8.3.</span> A CONTRATADA não fará uso, comercial ou de qualquer outra natureza, dos dados pessoais coletados por meio do sistema, nem os compartilhará com terceiros, salvo mediante autorização expressa e por escrito da CONTRATANTE ou em cumprimento a determinação legal.</div>

<h2>Cláusula 9ª — Da Garantia Técnica</h2>
<div class="item"><span class="n">9.1.</span> A CONTRATADA prestará <b>garantia técnica gratuita pelo prazo de 60 (sessenta) dias corridos</b>, contados da data de entrega e homologação do sistema, compreendendo a correção de defeitos, falhas e bugs identificados nas funcionalidades originalmente contratadas.</div>
<div class="item"><span class="n">9.2.</span> A garantia prevista no item anterior <b>não abrange</b>: (a) novas funcionalidades ou alterações de escopo; (b) falhas decorrentes de serviços de terceiros, tais como indisponibilidade da Locaweb, do Cloudinary ou do provedor de internet; (c) alterações realizadas por terceiros no código-fonte após a entrega; e (d) uso do sistema em desacordo com as orientações prestadas pela CONTRATADA.</div>

<h2>Cláusula 10ª — Da Multa por Atraso</h2>
<div class="item"><span class="n">10.1.</span> O descumprimento do prazo previsto na Cláusula 3ª, por causa exclusivamente imputável à CONTRATADA, ensejará <b>multa moratória de 1% (um por cento) do valor total do Contrato por dia de atraso</b>, limitada a 10% (dez por cento) do valor total contratado.</div>
<div class="item"><span class="n">10.2.</span> Não se configurará atraso imputável à CONTRATADA a demora, pela CONTRATANTE, no fornecimento de informações, conteúdos, credenciais ou aprovações necessárias à execução dos serviços, hipótese em que o prazo será prorrogado por período equivalente ao da pendência.</div>

<h2>Cláusula 11ª — Da Rescisão</h2>
<div class="item"><span class="n">11.1.</span> O presente Contrato poderá ser rescindido: (a) por mútuo acordo entre as Partes, formalizado por escrito; (b) por descumprimento de obrigação essencial por qualquer das Partes, não sanado no prazo de 10 (dez) dias corridos após notificação escrita; e (c) em caso de falência, recuperação judicial ou insolvência de qualquer das Partes.</div>
<div class="item"><span class="n">11.2.</span> Em caso de rescisão por culpa exclusiva da CONTRATADA, os valores já pagos serão devolvidos à CONTRATANTE, deduzidas as parcelas correspondentes aos serviços efetivamente entregues e aceitos.</div>
<div class="item"><span class="n">11.3.</span> Em caso de rescisão por culpa exclusiva da CONTRATANTE, esta pagará à CONTRATADA os serviços já executados até a data da rescisão, comprovados por evidência de entrega parcial.</div>

<h2>Cláusula 12ª — Das Disposições Gerais</h2>
<div class="item"><span class="n">12.1.</span> Este Contrato representa o acordo integral entre as Partes quanto ao seu objeto, prevalecendo sobre quaisquer entendimentos anteriores, verbais ou escritos.</div>
<div class="item"><span class="n">12.2.</span> Qualquer alteração deste Contrato somente terá validade se formalizada por termo aditivo escrito, assinado por ambas as Partes.</div>
<div class="item"><span class="n">12.3.</span> A tolerância de qualquer das Partes quanto ao descumprimento de cláusula deste Contrato não constituirá novação, permanecendo em pleno vigor as demais disposições.</div>
<div class="item"><span class="n">12.4.</span> O presente Contrato não estabelece qualquer vínculo empregatício, societário ou de exclusividade entre as Partes, sendo a CONTRATADA prestadora de serviços autônoma.</div>

<h2>Cláusula 13ª — Do Foro</h2>
<div class="item"><span class="n">13.1.</span> As Partes elegem o <b>foro da Comarca de Caxias do Sul, Estado do Rio Grande do Sul</b>, com renúncia expressa a qualquer outro, por mais privilegiado que seja, para dirimir quaisquer questões oriundas do presente Contrato.</div>

<p style="margin-top:16px;">E, por estarem assim justas e contratadas, as Partes firmam o presente instrumento em 2 (duas) vias de igual teor e forma, na presença das testemunhas abaixo identificadas, para que produza os seus jurídicos e legais efeitos.</p>

<div class="sign">
  <p class="place">Caxias do Sul/RS, ______ de ______________________ de 2026.</p>

  <div class="row">
    <div class="box">
      <div class="line">
        <b>VIDROFORTE INDÚSTRIA E COMÉRCIO DE VIDROS LTDA.</b>
        <span>CNPJ 92.639.954/0002-48 — CONTRATANTE</span>
      </div>
    </div>
    <div class="box">
      <div class="line">
        <b>GABRIEL PEREIRA DE SOUZA — STUDIO H5</b>
        <span>CNPJ 51.965.304/0001-24 — CONTRATADA</span>
      </div>
    </div>
  </div>

  <div class="test">
    <b>Testemunhas:</b>
    <div class="t"><span>1. Nome:</span><span>CPF:</span></div>
    <div class="t"><span>2. Nome:</span><span>CPF:</span></div>
  </div>
</div>

</body>
</html>"""

with open("contrato.html","w",encoding="utf-8") as f:
    f.write(html)
print("contrato.html gerado")
