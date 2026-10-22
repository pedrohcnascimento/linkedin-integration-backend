# Planejamento — integração de fontes externas de vagas

**Status:** planejamento de produto; ainda não implementado.  
**Escopo mínimo desejado:** concluir a Fase 1 e avaliar/concluir a Fase 2. A Fase 3 é opcional e depende dos resultados, do orçamento e das condições de licenciamento.  
**Objetivo:** ampliar a descoberta de oportunidades por meio de APIs autorizadas, comparando cobertura, relevância e custo.

> Preços, franquias e termos podem mudar. Revalidar as páginas oficiais antes de implementar ou contratar. Este documento registra uma direção de produto, não comprova integrações já existentes.

## 1. Objetivo e motivação

O projeto poderá ajudar o usuário a encontrar vagas compatíveis com seu perfil e organizar oportunidades localmente. A decisão sobre fontes deve priorizar dois fatores:

1. **Quantidade e amplitude:** vagas únicas e relevantes, variedade de empresas, funções, localidades e modalidades, com atenção especial ao mercado brasileiro.
2. **Custo total:** mensalidade, limite de chamadas, tarifas adicionais, obrigações de atribuição, permissões de redistribuição e eventual licença comercial.

Número bruto de resultados não basta: vagas duplicadas, expiradas, irrelevantes ou sem autorização clara de exibição não representam ganho real. As fases iniciais devem produzir dados comparáveis antes de qualquer compromisso financeiro.

## 2. Princípios e limites

- Não fazer scraping do LinkedIn, automatizar navegador, login, cliques ou endpoints internos não documentados.
- Usar apenas APIs e fontes cujo acesso e uso pretendido estejam autorizados pelos termos aplicáveis.
- Não presumir que um agregador tem direito de redistribuir todo conteúdo que encontra. Confirmar origem, atribuição, links de saída, retenção e uso comercial.
- Preservar a fonte e o link original de cada anúncio e respeitar as exigências de atribuição.
- Não automatizar candidaturas nem ações na conta do LinkedIn. O usuário decide manualmente como prosseguir.
- Manter adaptadores independentes por provedor, para que fontes possam ser substituídas ou desligadas.
- Não contratar planos pagos sem decisão explícita baseada em resultados medidos.
- Usar somente os dados necessários e definir retenção adequada antes de guardar descrições completas.

## 3. Plano de implementação em três fases

### Fase 1 — Base gratuita e arquitetura de fontes

**Combinação inicial: Greenhouse + Lever + Arbeitnow.**

- **Greenhouse Job Board API:** acesso a vagas publicadas em quadros de empresas conhecidos. É pública, mas não constitui um buscador global; é necessário identificar os quadros de empresas relevantes.
- **Lever Postings API:** acesso a anúncios públicos de empresas que utilizam Lever, também a partir de identificadores/quadro conhecidos; não oferece busca global universal.
- **Arbeitnow:** fonte pública sem chave, útil para ampliar variedade, mas com foco predominante na Europa e no Reino Unido. A cobertura do Brasil pode ser limitada e precisa ser medida.

**Motivo:** validar integração, normalização, deduplicação e experiência de busca com custo inicial estimado de US$ 0/mês, sem criar a expectativa de cobertura ampla do mercado brasileiro.

**Entregas esperadas:**
- Catálogo de fontes e resumo das condições de uso.
- Modelo interno comum de oportunidade, independente dos formatos externos.
- Importação de título, empresa, localização, URL original, fonte, modalidade e datas quando disponíveis.
- Deduplicação básica por URL e, quando necessário, por combinação de empresa, título e localização.
- Busca e filtros locais somente sobre atributos realmente disponíveis.
- Identificação clara da fonte e abertura do anúncio original pelo usuário.
- Métricas por fonte: resultados recebidos, válidos, únicos, campos ausentes e falhas.
- Conjunto fixo de consultas de teste, incluindo desenvolvimento, estágio/júnior, São Paulo e remoto no Brasil.
- Revisão dos termos antes de disponibilizar ou redistribuir anúncios.

**Critério de avanço:** fluxo reproduzível, dados normalizados, deduplicação e links preservados, testes documentados e compreensão das lacunas de cobertura.

### Fase 2 — Avaliação do JSearch Basic

**Combinação principal a testar: JSearch Basic + Greenhouse + Lever.** Arbeitnow pode continuar ativo como referência de comparação e fonte complementar.

O plano Basic anunciado no RapidAPI oferece **200 requisições por mês**, com limite rígido. É adequado a um experimento controlado, mas pode ser insuficiente para uso frequente ou múltiplos usuários.

**Motivo:** avaliar se um agregador aumenta a cobertura sem mensalidade, reduzindo a necessidade de conhecer previamente os quadros de cada empresa. O ganho no Brasil, a relevância, a sobreposição e os direitos de uso precisam ser verificados — não presumidos.

**Entregas esperadas:**
- Adaptador JSearch isolado do restante da aplicação.
- Chave armazenada fora do Git e controle explícito da cota mensal.
- Tratamento de cota esgotada, falhas de autenticação e indisponibilidade.
- Consultas comparáveis às da Fase 1.
- Relatório por fonte com resultados brutos, válidos, únicos e relevantes; sobreposição; diversidade de empresas, funções, localidades e modalidades; chamadas consumidas; anúncios desatualizados ou sem link claro.
- Verificação de proveniência e termos antes de expor resultados ao usuário.
- Decisão registrada: manter o Basic, continuar apenas com fontes gratuitas ou avaliar a Fase 3.

**Critério de avanço:** demonstrar ganho real de cobertura/relevância que justifique a complexidade e o consumo das 200 requisições mensais.

### Fase 3 — Expansão opcional com plano pago ou licença

**Hipótese de plano pago:** JSearch Pro, anunciado por **US$ 25/mês para 10.000 requisições mensais**. Confirmar preço, franquia, tarifas adicionais e termos vigentes antes de decidir.

**Combinação candidata:** JSearch Pro + Greenhouse + Lever + Arbeitnow, mantendo as fontes gratuitas que acrescentarem vagas únicas e relevantes.

**Adzuna fica como alternativa em avaliação, não como dependência aprovada para produção.** A documentação pública descreve limites padrão de 25 requisições por minuto, 250 por dia, 1.000 por semana e 2.500 por mês. Esses limites não garantem licença gratuita contínua para qualquer finalidade. Os termos podem restringir determinados usos e exigir autorização/licença escrita para uso contínuo. Como o preço comercial não está publicamente definido de forma suficiente para prever uma mensalidade confiável, obter confirmação por escrito sobre finalidade, redistribuição, atribuição e preço antes de produção.

**Entregas esperadas caso a fase seja aprovada:**
- Comparação de custo mensal total, franquia e cobertura medida na Fase 2.
- Confirmação contratual dos direitos de uso e redistribuição antes de contratar.
- Estimativa de consumo baseada em consultas reais, usuários esperados e frequência de atualização.
- Controles de cota/orçamento e possibilidade de desligar uma fonte sem interromper as demais.
- Testes de degradação quando um fornecedor falhar ou atingir a cota.
- Revisão periódica de custo, qualidade, duplicatas e ganho incremental.
- Decisão explícita e documentada sobre contratação.

**Critério de aprovação:** o ganho em vagas únicas e relevantes deve compensar custo e obrigações. Se o ganho for pequeno, permanecer nas fases anteriores é válido.

## 4. Combinações candidatas

Os níveis de cobertura são **estimativas qualitativas para priorização**, não contagens observadas. Não há uma métrica pública única e comparável de cobertura entre os provedores; o benchmark das Fases 1 e 2 deve substituir essas estimativas.

| Combinação | Custo inicial estimado | Cobertura potencial | Pontos fortes | Limitações |
|---|---:|---|---|---|
| **1. Greenhouse + Lever + Arbeitnow** | **US$ 0/mês** | Baixa a média; Brasil incerto | Base gratuita para validar arquitetura e normalização | Greenhouse/Lever exigem quadros conhecidos; Arbeitnow tende a favorecer Europa/Reino Unido |
| **2. Greenhouse + Lever + Arbeitnow + Remotive** | **US$ 0/mês** | Baixa a média, com reforço remoto | Pode adicionar vagas remotas | Remotive informa atraso de 24 horas e exige atribuição/link; restringe determinados usos e redistribuição para plataformas terceiras. Integrar somente após confirmar que o uso pretendido é permitido |
| **3. JSearch Basic + Greenhouse + Lever** | **US$ 0/mês; 200 requisições/mês no Basic** | Média a alta potencial, a validar | Teste de agregação sem mensalidade | Cota pequena; conferir proveniência, sobreposição e direitos de exibição |
| **4. Jooble + Greenhouse + Lever** | **US$ 0 inicial; 500 requisições vitalícias por chave informadas** | Média potencial, a validar | Experimento complementar | 500 não é franquia mensal; confirmar cobertura regional e termos |
| **5. Adzuna + Greenhouse + Lever** | **Preço comercial não confirmado** | Média a alta potencial, a validar | API de busca e limites de chamadas documentados | Uso contínuo pode exigir licença negociada; confirmar preço e direitos por escrito |
| **6. JSearch Pro + Greenhouse + Lever + Arbeitnow** | **US$ 25/mês anunciados para 10.000 requisições JSearch Pro** | Alta potencial, a validar | Franquia maior, com fontes gratuitas complementares | Candidata à Fase 3, não decisão de compra; confirmar termos e tarifas |

### Recomendação inicial

- **Melhor ponto de partida pelo preço:** combinação 1.
- **Melhor experimento de expansão sem mensalidade:** combinação 3.
- **Alternativa a avaliar com cautela:** combinação 5, após esclarecimento contratual da Adzuna.
- **Candidata à expansão paga:** combinação 6, se o Basic demonstrar valor suficiente.
- **Especialista em vagas remotas:** Remotive, condicionada à compatibilidade dos termos.
- **Experimento alternativo pequeno:** Jooble, lembrando que a cota gratuita informada é vitalícia, não mensal.

Não é necessário implementar todas as opções. O objetivo é medir e manter apenas as fontes que agregam vagas úteis e cujo uso seja permitido.

## 5. Como comparar cobertura, amplitude e custo

Usar um conjunto fixo de consultas — por exemplo, desenvolvimento, estágio/júnior, São Paulo e remoto no Brasil — e registrar data, parâmetros e limitações.

Indicadores recomendados:
- **Vagas brutas:** resultados antes da limpeza.
- **Vagas únicas:** após deduplicação.
- **Vagas relevantes:** atendem aos termos, nível, localidade e modalidade esperados.
- **Cobertura incremental:** vagas únicas e relevantes acrescentadas por cada fonte.
- **Amplitude:** diversidade de empresas, funções, localidades e modalidades.
- **Atualidade:** anúncios aparentemente ativos e datas confiáveis, quando disponíveis.
- **Custo por resultado útil:** custo mensal dividido pelas vagas únicas e relevantes, com metodologia registrada.
- **Consumo:** requisições por pesquisa e projeção mensal.
- **Qualidade:** proveniência, link original, campos disponíveis, estabilidade e compatibilidade dos termos.

Não comparar APIs apenas pelo volume bruto: muitas duplicatas podem fazer uma fonte parecer melhor do que realmente é.

## 6. Evolução funcional possível

Após a busca, as oportunidades poderão ser organizadas localmente, filtradas e marcadas com estados como “para revisar”, “salva” ou “candidatura iniciada manualmente”. Uma etapa posterior pode estimar compatibilidade com o perfil informado, explicando os critérios e sem prometer contratação.

Não presumir que conectar o LinkedIn concede acesso a currículo completo, histórico, competências ou dados de vagas. Usar apenas dados permitidos pela API oficial e pelos escopos concedidos; quando necessário, permitir que o usuário informe os dados manualmente.

## 7. Referências oficiais para revalidar

- [Greenhouse Job Board API](https://docs.greenhouse.io/job-board)
- [Lever Postings API](https://github.com/lever/postings-api)
- [Arbeitnow Job Board API](https://blog.arbeitnow.com/blog/job-board-api)
- [JSearch — planos no RapidAPI](https://rapidapi.com/letscrape-6bRBa3QguO5/api/jsearch/pricing)
- [Jooble — documentação REST](https://jooblehelpcenter.freshdesk.com/en/support/solutions/articles/60001448238-rest-api-documentation)
- [Adzuna Developer API](https://developer.adzuna.com/)
- [Adzuna — termos da API](https://developer.adzuna.com/docs/terms_of_service)
- [Remotive — API e condições de uso](https://remotive.com/remote-jobs/api)
- [LinkedIn User Agreement](https://www.linkedin.com/legal/user-agreement)
- [Termos de API do LinkedIn](https://developer.linkedin.com/legal)

## 8. Próxima ação

Primeiro, concluir o trabalho de confiabilidade já em andamento no fluxo de publicação. Em seguida, iniciar a Fase 1 com uma lista inicial de quadros Greenhouse/Lever relevantes e consultas de teste documentadas. Tratar a Fase 2 como experimento medido; manter a Fase 3 opcional até haver evidência de ganho de cobertura que justifique o custo e os termos do fornecedor.
