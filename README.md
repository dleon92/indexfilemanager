# Index File Manager

Gerenciador de arquivos Android ultraleve, focado em fotos e vídeos (pastas locais e Google Drive).

> Estado: **todas as fases concluídas** — fase 13 (Polimento e entrega) aprovada em 11/11/2026
> com a v0.13.1; versão atual v0.25.0 (`dist/index-v0.25.0-fase13.apk`, IndexWeb abre arquivos em nova aba, avisa antes de sair no meio de uma transferência, explica as falhas com "Tentar de novo" e pergunta se desliga ao fim do envio; senha salva no navegador, instalar como app e Locais na coluna de pastas; botões flutuantes Recentes, Downloads e IndexWeb (liga/desliga); IndexWeb: arquivos pelo navegador do computador, do celular ou do tablet, com 6 layouts à escolha; atalhos no ícone do app e de pastas na tela inicial; digitalizar documentos; player de vídeo; "Salvar no Index" no compartilhar; Recentes do Drive; leitor de documentos: PDF, Word (docx e
> doc) com formatação, aberto também por outros apps, Excel, PowerPoint, LibreOffice, texto, Markdown, CSV, RTF). A fase 4 (senha) foi absorvida pelas fases
> 6 (bloqueio do app) e 8 (pastas protegidas). Planejamento e histórico das decisões em `contexto.txt`.

**O que faz**: pastas locais (qualquer uma, inclusive Download) e do Google Drive numa grade de miniaturas que abre em
~0,15 s; pesquisa instantânea por índice local; streaming de vídeos do Drive para qualquer
player; visualizador de fotos próprio; edição (criar, renomear, copiar, mover, apagar, entre
local e Drive); bloqueio do app e pastas protegidas cifradas; seleção aleatória; pré-carga de
miniaturas; leitor de documentos (PDF, Office, LibreOffice, texto); 20 temas; tablet com dois
painéis, teclado e mouse.

## Compilar

Requisitos: JDK 17, Android SDK com `platforms;android-37` e `build-tools;37.0.0`
(`local.properties` com `sdk.dir=...`).

```
gradlew.bat assembleRelease
```

APK em `app/build/outputs/apk/release/app-release.apk`, assinado pela chave em `signing/`
(`index.keystore` + `keystore.properties`; desde a v0.13.6 é uma cópia da chave de debug desta
máquina, SHA-1 `B5:19:2C:…:60:58`, a mesma cadastrada no OAuth do Drive). **Guarde uma cópia de
`signing/` fora deste PC**: sem ela não dá para atualizar o app instalado. Sem `signing/`, o
release cai na chave de debug. Snapshots de cada fase ficam em `dist/`.

## Estrutura

```
app/src/main/java/app/index/files/
  IndexApp.kt        aplica o modo claro/escuro salvo antes de qualquer Activity (sem "piscar")
  Bg.kt              pool de 2 threads + Handler da main (sem coroutines)
  ui/                MainActivity (única) + Fragments: Home, ManageFolders, Browser, Search, Settings,
                     DrivePicker, LocalPicker, Protected. PullRefresh (puxar para atualizar). FlingBooster + FastScroller + ScrollLabels (rolagem rápida),
                     GridZoom (pinça), DragSelect, Keys, FileOpener, OpenWith (listas próprias de apps),
                     Share, FileEdit (Clip "Colar aqui", OpRunner com progresso), Auth (bloqueio), Themes
  ui/viewer/         ViewerFragment, ZoomImageView (zoom normalizado), ViewerLoader (base + recorte por região)
  ui/doc/            DocViewerFragment (leitor), DocKind, DocRender; PdfView (PdfRenderer com zoom);
                     TextDocs (txt, md, csv, rtf), Zip + Ooxml (xlsx/pptx), Docx (Word com formatação), Odf (odt/ods/odp),
                     Ole2 + Legacy (doc/xls/ppt), Sheet (tabela de planilha), Html (montagem e escape)
  data/FileOps.kt    criar pasta, renomear, apagar, copiar/mover (local ↔ Drive), enviar
  data/              AppDb (SQLite puro), Roots, LocalSource (SAF), FsSource (caminho), RecentSource (Recentes), VaultSource, ListingCache, Indexer,
                     Search, Sorting, RandomPick, Protection, Prefs
  data/drive/        DriveAuth (AuthorizationClient), DriveApi (REST v3 via HttpURLConnection + org.json),
                     DriveSource, DriveStreamProvider + StreamService (streaming)
  security/          AppLock, Vault (cofre AES-GCM no Keystore), Crypto, AppReset
  thumbs/            ThumbnailManager (memória+disco+filas), DiskStore (WebP, LRU), VaultThumbs, Preload, Decode
```

## Permissões

| Permissão | Por quê |
|---|---|
| `INTERNET` | Google Drive (listagem, miniaturas, streaming, edição). |
| `ACCESS_NETWORK_STATE` | Pré-carga "só no Wi-Fi". |
| `FOREGROUND_SERVICE` + `_MEDIA_PLAYBACK` | Mantém o streaming do Drive vivo enquanto o player (ou o app que recebeu um compartilhamento) lê. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Cópias/movimentações longas em segundo plano. |
| `USE_BIOMETRIC` | Desbloqueio por digital/rosto. |
| `WAKE_LOCK` | IndexWeb (v0.20.0): mantém CPU e Wi-Fi acordados com a tela apagada, só enquanto o acesso pelo computador estiver ligado. |
| `ACCESS_LOCAL_NETWORK` | IndexWeb (v0.20.0): no Android 17, sem ela o sistema derruba as conexões da rede local (o PC não alcança o celular). Pedida ao ligar o IndexWeb. |
| `POST_NOTIFICATIONS` | v0.22.0: no Android 13+ sem ela nenhuma notificação aparece (IndexWeb com o botão Desligar, streaming do Drive, cópias). Pedida uma vez ao ligar o IndexWeb; recusar não impede de ligar. |
| `CHANGE_WIFI_MULTICAST_STATE` | IndexWeb (v0.20.0): endereço fixo `index.local` (mDNS); o Wi-Fi só entrega multicast com a trava. Só com o IndexWeb ligado. |
| `MANAGE_EXTERNAL_STORAGE` | "Acesso a todos os arquivos" (Android 11+), ativado pelo usuário: adicionar pastas que o seletor do sistema recusa (Download inteira, raiz do armazenamento, raiz do cartão). |

Acesso amplo só se o usuário ativar: "Adicionar pasta local" (Android 11+) explica e abre a tela
do Android; recusada, cai no seletor do sistema (SAF). Pastas adicionadas antes continuam via SAF.
Android/data e Android/obb seguem inacessíveis (bloqueio do próprio Android). `<queries>`
(VIEW, SEND, SEND_MULTIPLE) declara quais apps o "Abrir com…" e o "Compartilhar" listam; não é
permissão.

## Decisões de leveza

- Três dependências só (tabela em "Dependências"); sem coroutines, Compose, Room, Glide/Coil,
  OkHttp, Retrofit, navegação ou preferências do AndroidX. R8 + encolhimento de recursos.
- Activity única com Fragments simples; giro de tela sem recriar a Activity.
- Drive por REST puro (`HttpURLConnection` + `org.json`), sem a biblioteca do Drive.
- Miniaturas: memória (≤ 32 MB) → disco WebP → geração, filas LIFO canceláveis; RGB_565.
- Visualizador: só 3 fotos na memória, na resolução da tela; nitidez do zoom por região.

- Listagem local com **uma única query** `DocumentsContract.buildChildDocumentsUriUsingTree`
  e projeção de 5 colunas; sem `DocumentFile`.
- Chave de ordenação (nome sem acento, minúsculo) calculada uma vez por item; caminho rápido
  para nomes ASCII. Ordem natural própria (`NaturalOrder`), sem `Collator`.
- Trocar ordenação reordena a lista em memória (não reconsulta o SAF). Recarregar a mesma
  pasta usa `DiffUtil` calculado em segundo plano; trocar de pasta substitui direto.
- Um único adapter e um único `GridLayoutManager` (lista = 1 coluna); células com 2 níveis
  de layout; `setHasFixedSize`; pool de views ampliado para a grade.
- Configurações com layout próprio (sem `androidx.preference`), ícones 100% vetoriais,
  só recursos em português (`localeFilters`).

## Achado de desempenho (fase 1)

Pasta com 5.006 itens no SM-S911B (Android 17):

| Etapa | Tempo |
|---|---|
| Query ao `ExternalStorageProvider` (sistema) | **~4,4 s** |
| Leitura do cursor + normalização + ordenação (app) | ~20 ms |
| Rolagem (fling) | 99% dos frames ≤ 14 ms; 0,25% janky |

O custo está no provedor do sistema, que consulta cada arquivo individualmente pelo FUSE;
nenhuma projeção ou opção de query reduz isso.

**Solução adotada: cache de listagem no SQLite** (tabelas `files` e `folders_cached`, que
serão o índice de busca da fase 3). Ao abrir uma pasta:

1. a listagem em cache é exibida imediatamente;
2. o SAF é consultado em segundo plano; o resultado é comparado com `DiffUtil` e só é
   aplicado/regravado se algo mudou.

| Abertura da pasta de 5.006 itens | Tempo até exibir |
|---|---|
| Primeira visita (sem cache) | ~4,5 s (indicador de carregamento) |
| Visitas seguintes | **19–22 ms** |
| Após reiniciar o app | **41 ms** |

O cache guarda também arquivos ocultos (o filtro é aplicado na exibição) e é apagado ao
remover ou readicionar a pasta.

## Miniaturas (fase 2)

- **Memória:** `LruCache` por bytes (1/8 do heap Java, **com teto de 32 MB**), bitmaps em
  RGB_565 quando opacos. *Desvio aprovado da especificação:* o teto existe porque cada bitmap
  vivo também mantém uma textura equivalente na GPU; com 1/8 (64 MB no S23) o custo dobra.
- **Disco:** `cacheDir/thumbs` ou `filesDir/thumbs` (configurável), um WebP q75 por arquivo,
  lado maior 256 px. Chave = FNV-1a 64 de `origem|id|data_mod|tamanho` (arquivo alterado ⇒
  chave nova ⇒ regeneração automática). Arquivo de 0 byte marca "sem miniatura possível".
  Limite configurável com remoção LRU (data de modificação atualizada nas leituras).
- **Geração:** fotos via `ImageDecoder` com `setTargetSize` (API 28+; aplica EXIF e suporta
  HEIC) ou `BitmapFactory` + `inSampleSize` + EXIF (API 26–27). Vídeos via
  `ContentResolver.loadThumbnail` (API 29+) com fallback para `MediaMetadataRetriever`.
  *Desvio da especificação:* para fotos não uso `loadThumbnail`, porque no
  `ExternalStorageProvider` ele tende a devolver a miniatura EXIF (~160 px), borrada numa
  célula de ~360 px.
- **Filas:** pools LIFO (disco: 2 threads; geração: 2 threads, baixa prioridade); pedido
  cancelado ao reciclar a view; pré-geração opcional numa thread de prioridade mínima.
- **Selo de vídeo** desenhado no `onDraw` da própria ImageView (sem layout extra).

Medições na pasta DCIM/Camera (910 fotos e vídeos), SM-S911B:

| Situação | Resultado |
|---|---|
| Geração (cache vazio) | foto ~90 ms, vídeo ~180 ms (2 em paralelo); 1ª tela completa em ~0,6 s |
| Reabrir na mesma sessão | 0 leituras: tudo da memória, no mesmo frame |
| Reabrir após reiniciar o app | leitura do disco 1,9 ms em média (máx. 7 ms), 0 regenerações |
| Fling pela pasta inteira gerando | 0,04% frames janky, p99 14 ms; 895 pedidos, só 234 gerados (resto cancelado) |
| Fling com tudo em cache | 0,04% janky, p99 14 ms |
| RAM (PSS) | 51 MB na tela inicial; 78 MB na 1ª tela da pasta; ~218 MB após rolar as 910; 37 MB em segundo plano |

Composição da RAM após rolar a pasta inteira (com teto de 32 MB, `dumpsys meminfo`/`gfxinfo`):

| Parte | Tamanho | Controlável pelo app? |
|---|---|---|
| Bitmaps das thumbs (`Bitmap (malloced)`) | 32,7 MB | Sim: é o teto do LruCache (antes ~60 MB) |
| Texturas da GPU desses bitmaps (HWUI) | 32,6 MB (381 entradas; 47 MB do cache GPU purgeável) | Indiretamente, pelo teto acima |
| Buffers da janela (4 × 1080×2340, 120 Hz) | ~40 MB | Não: custo do sistema para qualquer app em tela cheia |
| Demais pools do driver gráfico (GL/EGL/Gfx) | restante de "Graphics" | Não; liberados ao ir para segundo plano |

Com o teto de 32 MB a rolagem seguiu fluida (0,08% janky, p99 14 ms).

## Índice e pesquisa (fase 3)

- **Tabela única** `files` (raiz, doc_id, pasta-pai, caminho relativo, nome, nome normalizado,
  mime, tamanho, data, é_pasta) serve ao cache de navegação e à busca. A origem vem de
  `roots.source`; o `doc_id` é o id remoto no Drive (fase 5).
- **Indexador** (`Indexer`): thread única de prioridade mínima, sem WorkManager. Roda 1,5 s
  após abrir o app, ao adicionar/readicionar uma pasta e em "Reindexar". Percorre em largura;
  **incremental** pela data de modificação de cada pasta (se não mudou, os filhos vêm do
  índice). Pastas que sumiram são removidas do índice (`prune`). Atualiza a contagem de itens.
- **Busca:** `LIKE '%termo%'` sobre `name_norm` (sem acento, minúsculo), escopo pasta atual
  (prefixo de `parent_path`) ou todas, filtro Fotos/Vídeos por mime, ocultos respeitados.
  O SQL ordena e limita a 5.000; a ordem exata (natural, extensão) é refeita em memória com o
  mesmo comparador da navegação. Debounce de 250 ms.
- Resultados em grade ou lista com caminho relativo; toque longo → "Abrir" / "Ir para a pasta"
  (o caminho de ids é reconstruído subindo pelo índice).
- Remover uma pasta apaga suas linhas de `files`; a busca também faz JOIN com `roots`.

Medições (21 mil arquivos em 11 pastas, SM-S911B):

| Operação | Tempo |
|---|---|
| Primeira indexação (20.004 arquivos, 10 pastas) | 14,6 s em segundo plano (custo do SAF) |
| Reindexação incremental sem mudanças | 73 ms (0 consultas ao SAF) |
| Busca, termo raro (1–11 resultados) | 8–17 ms |
| Busca, termo comum (5.000+ resultados, truncado) | 27–36 ms |
| Filtros Fotos / Vídeos (Camera) | 13 / 12 ms (781 + 129 = 910 ✓) |

## Rolagem rápida (fase 5)

- **Fling acelerado** (`FlingBooster`): flings seguidos na mesma direção (intervalo < 0,9 s)
  multiplicam a distância natural 1× → 1,5× → 2× → 4× → 6× → 8× (suavizada na v0.10.2; antes
  1 → 1,8 → 3 → 5 → 8). Um fling isolado é idêntico ao do sistema.
- **Barra arrastável** (`FastScroller`): aparece ao rolar listas com mais de ~4 telas; tocar/arrastar
  na faixa da direita pula direto para a posição, com balão mostrando a letra/mês/tamanho/tipo conforme a ordenação.

Medido na pasta de 5.006 itens (posição alcançada a partir do topo):

| Gesto | Itens percorridos |
|---|---|
| 1 fling | 81 |
| 2 flings seguidos | 214 |
| 3 flings seguidos | 376 |
| 4 flings seguidos | 615 |
| 5 flings seguidos | 1.049 (2,6× o que 5 flings normais dariam) |
| Barra: arrastar até o fim / topo | instantâneo (arquivo1 / topo) |

## Google Drive (fase 5)

- Login só com `AuthorizationClient` (play-services-auth), escopo `drive.readonly`. Sem Credential Manager:
  o e-mail vem da própria API (`about`), o que dispensa `androidx.credentials` + `googleid`.
- Token em memória (~45 min); 401 ⇒ `clearToken` + nova autorização silenciosa; 403 de cota / 429 / 5xx ⇒
  backoff exponencial com jitter (até 6 tentativas).
- `DriveSource` implementa o mesmo `FileSource` da pasta local: cache de listagem, índice/busca, ordenação,
  miniaturas e rolagem são os mesmos. Miniaturas vêm do `thumbnailLink` do Drive (`=s256`), sem baixar o
  arquivo, e vão para o mesmo cache WebP. Atalhos do Drive abrem o alvo (com proteção contra ciclos).
- Abrir (configurável): **transmitir** (padrão), baixar para `cache/drive_open` (24 h, via `FileProvider`)
  ou abrir no Google Drive. Documentos Google sempre abrem no Drive.
- Indexação: o Drive não tem data de pasta confiável, então é relistado inteiro, no máximo a cada 12 h na
  abertura do app (sempre ao adicionar ou em "Reindexar"). Com falha de rede não há poda do índice.
- Sem rede: a pasta continua navegável pelo cache, com aviso. Desconectar revoga o acesso e apaga o índice do Drive.

### Streaming para outros apps (VLC, player nativo…)

- `DriveStreamProvider` entrega um `content://` cujo descritor tem acesso aleatório
  (`StorageManager.openProxyFileDescriptor`, API 26+). Nada é gravado em disco.
- **Leitura em blocos com download antecipado (v0.5.3):** blocos de 128 KB num cache em memória de até
  32 MB por arquivo aberto (só durante o streaming). Até 3 "faixas" baixam em segundo plano, cada uma numa
  conexão sequencial (`Range: bytes=N-`), mantendo 8 MB à frente da última leitura da sua região; um salto do
  player reposiciona a faixa usada há mais tempo. Blocos já no cache não são baixados de novo.
  Motivo: o player nativo da Samsung lê áudio e vídeo em pontos alternados e relê trechos; servido sob demanda
  numa só conexão, cada alternância virava uma requisição (~45 em 50 s) e o vídeo congelava a cada ~5 s.
- Faixas, conexões **e o cache** liberados após 20 s sem leituras (vídeo pausado; o VLC segura o descritor até o
  processo dele morrer) e recriados na próxima leitura (~1 s).
- Ao sair da tela (`onTrimMemory` UI_HIDDEN) o app solta as miniaturas da RAM (ficam no disco). Medido com vídeo
  pausado no player nativo: **51–56 MB de PSS** (antes ~120 MB + até 32 MB de cache do streaming); retomada sem erro.
- **Serviço em primeiro plano** (`StreamService`, tipo `mediaPlayback`) iniciado no toque do arquivo: sem ele o
  Android 17 põe o processo em cache ~20 s depois que o player assume a tela e corta a rede
  (`UnknownHostException`, medido). Fica ativo **enquanto algum player estiver com um stream aberto** (pausas
  longas funcionam); para 15 s depois que o último stream é fechado, pelo botão "Parar" da notificação ou, por
  segurança, após 2 h sem leituras. O VLC só fecha o arquivo quando o processo dele termina.
  Exige `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (concedidas na instalação, sem pedido) —
  exceção aprovada à regra "só INTERNET".

Medido no VLC (SM-S911B, Wi-Fi):

| Caso | Resultado |
|---|---|
| Vídeo de 15 s (2,7 MB) | abre e toca em < 1 s (buffer de 1,75 s pronto em 859 ms), até o fim |
| Vídeo de 4,5 GB / 48 min, índice MP4 de 6,7 MB | começa em ~3,5 s; 2 min tocando + 2 saltos sem erro; 8 requisições, 155 MB lidos |
| Mesmo vídeo **sem** o serviço | erro de E/S ~25 s após o início (rede cortada) |
| Pausa de 6 min e retomada (2 testes) | serviço ativo o tempo todo, processo em primeiro plano, sem erro ao retomar |
| VLC fechado pelos recentes | o processo do VLC continua vivo segurando o arquivo; o serviço só para quando ele morre (15 s depois, medido) ou pelo teto de 2 h |

Player nativo da Samsung (vídeo de 1 GB):

| Versão | Resultado |
|---|---|
| v0.5.1 (sob demanda, 1 conexão) | áudio liso, imagem congela ~5 s a cada poucos quadros; 41 requisições em 47 s |
| v0.5.2 (cache + 4 conexões) | melhor, ainda com travadas; 12 requisições, 67 MB baixados para 25 MB lidos |
| v0.5.3 (download antecipado) | liso; 3 requisições, 20 esperas somando 3,2 s (início e saltos) |

### Configurar o OAuth (uma vez)

1. Em <https://console.cloud.google.com> crie um projeto e ative a **Google Drive API**.
2. **Tela de consentimento OAuth**: tipo "Externo", status "Em teste", adicione sua conta em "Usuários de teste"
   e o escopo `.../auth/drive` (leitura e edição desde a fase 7; escopo restrito: para publicar
   é preciso verificação do Google).
3. **Credenciais → Criar ID do cliente OAuth → Android**: pacote `app.index.files` e o SHA-1 da chave que assina o APK.
   Chave de debug desta máquina (também assina o release enquanto não houver `signing/`):
   `B5:19:2C:C8:90:87:4C:06:28:72:FE:D9:F3:1A:A7:C7:D4:67:60:58`
   (`keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`).
4. Não há arquivo de configuração no app: o Google associa pacote + SHA-1 ao cliente. Pode levar alguns minutos para valer.

## Bloqueio do app (fase 6)

- Configurações → Segurança. Dois métodos: **bloqueio do celular** (PIN/padrão/senha/biometria
  do aparelho via `BiometricPrompt` com `DEVICE_CREDENTIAL`; `KeyguardManager` em API 26–28) ou
  **senha própria** (PIN 4–8 dígitos ou senha 4–64 caracteres) com biometria opcional.
- Só o hash fica salvo: PBKDF2WithHmacSHA256, 120 mil iterações, salt aleatório de 16 bytes,
  comparação em tempo constante (`MessageDigest.isEqual`), em SharedPreferences `lock`.
- Após 5 erros, cada novo erro impõe **5 min** de espera (persistida; vale mesmo fechando o app).
- Estado "desbloqueado" só na memória: processo novo começa bloqueado. Rebloqueio
  imediatamente / 30 s / 1 min / 5 min após sair. Seletor de pastas, login Google e a tela de
  senha do aparelho não contam como saída.
- `FLAG_SECURE` ligado por padrão (apps recentes em branco, sem captura de tela).
- Se o bloqueio de tela do celular for removido, o app exige criar uma senha própria.
- "Esqueci a senha → Redefinir app" apaga configurações, índice, miniaturas, pastas e login do
  Drive (nunca os arquivos) e reinicia.
- Permissão nova: `USE_BIOMETRIC` (normal, concedida na instalação; não dá acesso a dados biométricos).

## Edição de arquivos (fase 7)

- Toque longo (ou ⋮ → Selecionar) entra no modo de seleção: Renomear, Copiar, Mover, Apagar,
  Selecionar tudo. Copiar/Mover guardam os itens e mostram "Colar aqui" em qualquer pasta.
  ⋮ → Nova pasta / Enviar arquivos para cá (seletor do sistema, vários arquivos).
- Contrato único em `FileSource` (createFolder, rename, delete, moveWithin, copyWithin,
  openRead, write); `FileOps` faz o resto igual para SAF e Drive, com cópia recursiva de pastas.
- Mesma raiz local: `DocumentsContract.moveDocument` (sem copiar bytes); entre Drives: PATCH de
  parents e `files.copy` no servidor. Entre origens: stream de 256 KB (upload retomável num PUT
  só no Drive). Arquivo pela metade é apagado em falha ou cancelamento.
- Apagar: local é definitivo (SAF não tem lixeira; o aviso diz isso); Drive vai para a lixeira.
- Nomes repetidos viram "nome (1).ext". Pasta para dentro dela mesma é recusada.
- Atalhos do Drive: renomear/mover/apagar é recusado (mexeria no original em outra pasta).
- Depois de cada operação: listagens das pastas mexidas regravadas no cache, descendentes de
  pastas apagadas/movidas removidos do índice, pastas novas indexadas (`Indexer.startSubtree`),
  thumbs antigas apagadas.
- Permissões: SAF de leitura **e escrita** (pastas antigas: readicionar uma vez, índice mantido);
  Drive passa a usar o escopo `drive` (reconectar uma vez).
- Segundo plano (v0.7.1): `TransferService` (serviço em primeiro plano tipo dataSync) ativo só
  durante a operação; cópias e envios continuam com o app fora da tela ou a tela apagada.
  Notificação sem nomes de arquivos (só "Item x de n" e botão Cancelar). Android 15+: limite de
  6 h/dia do tipo dataSync (`onTimeout` cancela a operação). Sem `POST_NOTIFICATIONS`, no Android
  13+ a notificação só aparece no gerenciador de tarefas. A janela de progresso é escondida
  enquanto o app está bloqueado.

## Pastas protegidas (fase 8)

- Ocultas só dentro do Index: nada muda nos arquivos (sem `.nomedia`, sem renomear). Proteger
  por seleção (⋮ → Proteger pasta) ou em Gerenciar pastas; acesso por ⋮ → Pastas protegidas.
- Cofre `files/vault/vault.bin`: JSON cifrado com AES-256-GCM (IV de 12 bytes + texto cifrado).
  A chave mestra fica no AndroidKeyStore (`index_vault`); dentro do cofre vai uma chave de dados
  aleatória, as entradas (raiz, ids e nomes do caminho, método) e a senha da lista, se houver.
- Abrir a lista: senha do app se o bloqueio estiver ativo, senão o bloqueio do celular, senão uma
  senha própria das pastas protegidas. Cada pasta: senha do app, bloqueio do celular ou senha
  própria (PBKDF2). Celular sem bloqueio → pasta passa a pedir
  senha própria.
- Ocultação: filtro no cache de listagem, no indexador (walk, raiz, subárvore), em `Roots.all`,
  na listagem do navegador e no seletor do Drive; contagens refeitas sem ela. Readicionar a
  pasta-raiz protegida é ignorado em silêncio.
- Índice próprio por pasta (`files/vault/<id>/index.bin`, cifrado; só em memória enquanto aberta)
  com busca dentro dela. Miniaturas cifradas em `files/vault/<id>/t/` (`VaultThumbs`).
- Sessão só em memória: tranca ao fechar a lista ou após o tempo fora do app (0/1/5/15 min,
  padrão 5). FLAG_SECURE sempre que alguma pasta protegida estiver aberta.
- Renomear/mover pasta local que contém pasta protegida atualiza o id dela (`Vault.remap`);
  copiar uma pasta que contém pasta protegida é recusado.
- Apagar pela listagem comum uma pasta que contém uma protegida é recusado (v0.13.6), com o mesmo
  erro genérico da cópia (`Vault.containsProtected`, pela origem e, no aparelho, pelo caminho).
  Até a v0.13.5 a protegida era apagada junto.

## Seleção aleatória (fase 9)

- Botão de dado na navegação e na pesquisa: toque sorteia, toque longo abre o painel
  (quantidade 5/10/20/50/100 ou 1–1000, tipo fotos/vídeos/ambos, subpastas na navegação). Os
  valores ficam como padrão (`Prefs.random*`, também em Configurações → Seleção aleatória).
- `RandomPick`: origem filtrada pelo tipo e sem ids repetidos; Fisher–Yates parcial com
  `SecureRandom` (sem repetição; origem menor que N → todos, embaralhados).
- Navegação: origem = itens visíveis da pasta (mesmos filtros da listagem); com subpastas,
  mais `ListingCache.filesUnder` / `VaultIndex.filesUnder` (pula ocultos e pastas protegidas).
- Pesquisa: origem = resultados com os filtros ativos; se cortados em 5.000, a consulta é refeita
  sem o corte (`SearchQuery.limit`) só para o sorteio.
- A seleção sorteada substitui a lista: barra "N aleatórios de M" com "Sortear de novo" e
  "Lista completa" (ou voltar, que restaura a rolagem); ordenação e grade/lista valem para ela;
  sobrevive à ida a outra tela. Sem edição enquanto ativa (itens podem vir de subpastas).

## Pré-carga de miniaturas (fase 10)

- Só sob pedido: ⋮ → Carregar todas as miniaturas (pasta, pesquisa ou seleção sorteada).
  Substitui a antiga opção "pré-gerar miniaturas ao abrir a pasta".
- `thumbs/Preload`: 3 tarefas em paralelo, do item visível para fora; gera/baixa no disco o que
  falta (nunca os originais) e carrega na RAM (RGB_565, 256 px) até o orçamento.
- Orçamento: 1/4 de `ActivityManager.memoryClass` (máx. 1/3 do heap; sem largeHeap). Durante a
  sessão o LruCache comum só ganha esse teto (`resize`), então a RAM nunca passa dele; ao
  liberar volta ao teto normal (32 MB) e o excedente sai. Se não coube tudo, ao parar a rolagem
  carrega do disco a janela de ~3 telas acima e abaixo.
- Progresso "1.240 / 5.000 (54 MB)" com Cancelar; raio na barra superior enquanto há RAM extra
  (toque: situação, Parar/Liberar). Aviso antes de começar se o que falta não cabe no limite do
  cache em disco (estimativa de 16 KB por miniatura).
- Liberação da RAM (o disco fica): sair da lista sem voltar (1 min), app em segundo plano
  (2 min), sem interação (5 min) — configuráveis em 1/2/5/10 min; `onTrimMemory` por falta de
  memória libera na hora. Trancar pasta protegida também libera.
- Drive "só via Wi-Fi" (padrão ligado): miniaturas do Drive puladas em dados móveis (contadas no
  aviso final). Permissão nova: `ACCESS_NETWORK_STATE` (normal; só lê o tipo de conexão).

## Temas (fase 11)

- 20 temas só de cores (sem imagens): claros Papel, Areia, Céu, Menta, Lavanda, Pêssego, Nuvem,
  Limão, Rosa Antigo, Ártico; escuros OLED Puro, Grafite, Meia-Noite, Floresta, Vinho, Âmbar,
  Ametista, Oceano, Café, Neon. Cada um é um estilo `Theme.Index.<id>` que define os atributos
  `idx*` (fundo, superfície, barra superior, barra de navegação, texto, texto secundário,
  destaque, texto sobre destaque, seleção, aviso). `Theme.Index.Base` liga os atributos do
  sistema a eles; `@color/bg`, `@color/text` etc. são seletores em `res/color/` que apontam para
  `?attr/idx*`, então layouts e vetores existentes seguem o tema sem mudanças.
- Contraste WCAG AA (≥ 4,5:1) conferido por script em todos os pares: texto/fundo, texto/
  superfície, texto/barra, secundário/fundo e superfície, destaque/fundo e superfície,
  texto sobre destaque, aviso/fundo e superfície. Pior caso: 4,56:1 (destaque do Areia).
- Claro e escuro escolhidos à parte (Configurações → Aparência → Tema claro / Tema escuro), em
  grade com prévia desenhada (`ThemeUi.Preview`: barra, grade com item selecionado, botão,
  barra de navegação). Modo: seguir o sistema, sempre claro, sempre escuro ou por horário
  (padrão escuro das 19:00 às 07:00; reavaliado ao abrir e na virada, com o app na tela).
- Sem "piscar": o estilo é aplicado antes de `super.onCreate`; no Android 12+ o modo vai para
  `UiModeManager.setApplicationNightMode` (o sistema já abre a janela no claro/escuro certo);
  antes disso, `AppCompatDelegate`. A janela de abertura usa Papel/Grafite.
- Edge-to-edge em todas as versões: a toolbar cresce sob a barra de status (cor da barra
  superior) e o fundo atrás da barra de navegação/teclado usa a cor da barra de navegação.
- "Preto real" virou o tema OLED Puro (quem usava começa nele).

## Seleção por arraste (v0.11.1)

- Navegador (grade e lista): toque longo num item e, sem soltar, arrastar desenha uma área
  retangular; todos os itens que ela toca entram na seleção. Começando num item que ficou
  desmarcado, a área desmarca. Ao encolher a área, os itens voltam ao estado anterior.
- Perto da borda de cima/baixo (~72 dp) a lista rola sozinha, mais rápido quanto mais perto; a
  área fica presa ao ponto inicial e acompanha a rolagem.
- `ui/DragSelect` (ItemDecoration + OnItemTouchListener): células de tamanho único, então a área
  vira um bloco linhas × colunas calculado por posição (itens fora da tela também entram); a cada
  movimento só a união do bloco antigo com o novo é revisitada. Sem dependências novas.
- Fora da seleção aleatória (sem edição) e da pesquisa (sem modo de seleção).

## Tablet, teclado, mouse e pinça (fase 12)

- **Colunas pela largura**: a preferência é o tamanho mínimo da miniatura (`Prefs.thumbDp`,
  em Configurações → Tamanho mínimo das miniaturas); colunas = largura da lista ÷ tamanho.
  `ui/GridZoom` recalcula a cada mudança de largura/altura da lista (girar, multi-janela,
  painel lateral) mantendo o item do topo no lugar. Quem usava "N colunas" começa no
  equivalente (3 colunas → 112 dp).
- **Pinça** (também Ctrl + roda do mouse, Ctrl + `+`/`−` e ⋮ → Miniaturas maiores/menores):
  degraus lista com detalhes → 64, 80, 96, 112, 128, 160, 200, 256, 320, 400, 560 dp →
  uma por tela. Degraus que não mudam as colunas na largura atual são pulados; o item sob os
  dedos fica parado. "Uma por tela": coluna única com altura limitada à tela e a imagem
  inteira (`FIT_CENTER`).
- **Miniaturas por largura da célula**: 256 px; 384 px acima de ~420 px de célula; 768 px
  acima de ~700 px (coluna única). Chave com sufixo por tamanho; apagar/mover limpa todos.
- **Dois painéis** com janela ≥ 840 dp (Tab S7+ nas duas orientações; S23 nunca): pastas
  fixas à esquerda (`HomeFragment.newPane`, 26% da largura, 260–360 dp), conteúdo à direita;
  escolher uma pasta à esquerda substitui o que está aberto. Sem layouts por qualificador:
  a Activity trata giro/redimensionamento sem recriar, então o painel é ligado em código.
- **Teclado**: setas navegam pelos itens (contorno de foco), Enter abre, Backspace/Esc
  voltam, Ctrl+F pesquisa (na pasta atual), Ctrl+A seleciona tudo, Delete apaga a seleção,
  F2 renomeia. **Mouse**: clique direito num item abre o menu de ações (Abrir, Renomear,
  Copiar, Mover, Apagar, Proteger); roda com Ctrl muda o tamanho.

- **Seleção no sorteio e na pesquisa** (v0.12.2): toque longo e arraste também no resultado do
  sorteio e na pesquisa. Sem edição ali (itens de várias pastas): "Abrir com…", "Selecionar
  tudo" e, na pesquisa, "Ir para a pasta". Na pesquisa o toque longo seleciona; as ações do
  item ficam no clique direito do mouse.
- **"Abrir com…"** (v0.12.3), com um arquivo selecionado: lista própria de apps (`ui/OpenWith`,
  `queryIntentActivities`), que não lê o arquivo — a janela do sistema lia o streaming do Drive
  inteiro antes de aparecer. O app escolhido recebe o mesmo Intent da abertura normal.
  Configurações → Abrir com… → Players da lista limita a lista aos players marcados (vazio =
  todos; se nenhum marcado abre o tipo, mostra todos). Manifesto: `<queries>` para VIEW de
  vídeo/imagem/áudio/arquivos (visibilidade de pacotes do Android 11+; não é permissão).

## Visualizador de fotos, compartilhar e listas de apps (v0.12.4)

- **Visualizador interno** (`ui/viewer`): tocar numa foto (pasta, sorteio, pesquisa) abre em
  tela cheia; vídeos seguem abrindo como antes. Configurações → Navegação → "Abrir fotos no
  visualizador do Index" (ligado por padrão). Páginas num `RecyclerView` com `PagerSnapHelper`,
  só fotos da lista, na ordem dela; barra (nome, "3 / 120", Compartilhar, Abrir com…) e setas
  laterais somem com um toque, junto com as barras do sistema. Teclado: setas/PgUp/PgDn/
  Home/End; Esc/Backspace fecha. Roda do mouse dá zoom. Ao fechar, a grade rola até a foto.
- **Zoom mantido entre fotos**: o estado do `ZoomImageView` é normalizado (zoom 1 = foto
  inteira; centro em fração da imagem), então vale para qualquer resolução e para a próxima
  foto — ela abre com o mesmo zoom na mesma região. As vizinhas já recebem o estado.
- **Leve** (`ViewerLoader`): miniatura da grade na hora (se estiver na RAM, `cachedAny`), depois
  a base com lado ≈ tela em RGB_565 (Drive: miniatura `=sN` até 1,5× a tela, sem baixar o
  original). Só 3 bases na memória (atual e vizinhas); tudo liberado ao fechar. Com zoom além
  da base, arquivos locais ganham um **recorte nítido** só da área visível
  (`BitmapRegionDecoder`, amostragem pela escala, rotação EXIF mapeada; espelhamentos sem
  recorte). `FileSource.openFd` dá o descritor (local; Drive não tem).
- **Compartilhar** (barra de seleção na pasta/sorteio/pesquisa, clique direito e visualizador):
  um ou vários arquivos (pastas não). Lista própria de apps, aberta na hora; locais vão com a
  Uri do SAF, do Drive com o mesmo link de streaming dos players (`FileSource.shareUri`;
  quem recebe lê direto, sem download prévio; o serviço de streaming se encerra sozinho se
  nada abrir). Manifesto: `<queries>` para SEND e SEND_MULTIPLE.
- **Listas de apps** em Configurações: "Players de vídeo da lista" (vídeo e áudio),
  "Visualizadores de imagem da lista" (fotos) e "Apps da lista de compartilhar". Vazio = todos;
  se nenhum marcado serve, mostra todos; "Mostrar todos os apps" no fim. Outros tipos no
  "Abrir com…" mostram todos os apps.
- **Configurações em todas as telas**: último item do menu ⋮ (menos na própria tela).

## Polimento (fase 13)

- **Pasta protegida órfã**: apagar uma pasta que continha uma pasta protegida deixava a entrada
  no cofre. Agora apagar (ou mover por cópia) tira do cofre as proteções dela e de dentro dela,
  com o índice e as miniaturas cifradas (`Vault.forgetDeleted`, `Protection.onFolderDeleted`).
  Entradas órfãs antigas saem com "Remover proteção" na lista.
- **Acessibilidade**: todos os botões de imagem têm descrição; itens selecionados são
  anunciados como "Selecionado" pelo TalkBack; fotos do visualizador são lidas pelo nome.
  Estados vazios com texto e ação (pasta vazia, sem pastas, sem resultados, Drive vazio).

- **Ícone (v0.13.2)**: "fichário com abas" na paleta "noite roxa" (fundo `#1B1830`, ficha
  clara, abas âmbar/verde/rosa), escolhido entre 5 propostas e 3 variações escuras. Adaptativo e
  100% vetorial, com camada monocromática para os ícones temáticos do Android 13+.
- **Configurações reorganizadas (v0.13.2)**, do uso diário ao raro: Aparência · Navegação
  (Exibição, Ordenação padrão, Arquivos e fotos) · Abrir e compartilhar (as 3 listas de apps) ·
  Seleção aleatória · Google Drive · Miniaturas · Pré-carga · Segurança · Dados do app
  ("Redefinir app" sozinho no fim, em cor de aviso) · Sobre (versão instalada). Seções separadas por linhas finas.
- **Pastas bloqueadas (v0.13.3)**: o seletor do sistema recusa a Download inteira e as raízes
  do armazenamento desde o Android 11. "Adicionar pasta local" agora abre um seletor próprio
  (`LocalPickerFragment`: volumes → pastas, mesmo layout do seletor do Drive) e a pasta é lida pelo
  caminho (`FsSource`, `java.io.File`; raiz com `source = file`). Ids são caminhos absolutos, então
  cofre, índice, busca e cópia/movimentação funcionam igual. Outros apps recebem Uri do FileProvider
  (`external-path`). Miniaturas: `ImageDecoder` pelo caminho; vídeo com `ThumbnailUtils`.
  Permissão revogada: a pasta aparece indisponível e "Readicionar" pede de novo.
  Testado no S23: Download com 394 itens, seletor, miniatura, visualizador e compartilhar.
- **Pastas sempre em dia (v0.13.4)**:
  - **Puxar para atualizar**: com a lista no topo, arrastar para baixo traz um círculo; soltando
    além do limite a pasta é consultada de novo (`PullRefresh`, sem biblioteca: um
    `OnItemTouchListener` que cede à pinça e ao arraste de seleção).
  - **Volta ao app**: a pasta aberta é consultada de novo (onStart) e o índice incremental roda
    outra vez se o app ficou mais de 10 s fora; só o que mudou é aplicado (DiffUtil).
  - **Aviso de atualizando**: o mesmo círculo gira quando a consulta passa de 400 ms com a lista
    do cache na tela (Câmera com 925 fotos: 1–4 s pelo SAF).
  - **Índice e subpastas**: a data de uma subpasta vinha da listagem guardada da pasta de cima,
    que não muda quando só a subpasta muda; arquivos novos em subpastas ficavam fora da busca e das
    contagens. Agora, com a pasta de cima reaproveitada, a data atual da subpasta é lida
    (`FileSource.folderMtime`: uma consulta de uma coluna no SAF; `lastModified` no caminho).
  - Testado no S23 com uma pasta temporária: volta pelos recentes, puxada, subpasta no índice.
- **Pasta inicial e Recentes (v0.13.5)**:
  - **Pasta inicial**: Configurações › Navegação › "Ao abrir o app" escolhe o que abre com o app
    (lista de pastas, Recentes ou uma pasta raiz). Subpastas pelo ⋮ da pasta ("Definir como pasta
    inicial"). Pasta removida, indisponível ou protegida: abre a lista; subpasta apagada: abre a raiz
    e ela passa a ser a inicial.
  - **Recentes**: pasta virtual (`RecentSource`, raiz -10 fora do banco) no topo da lista, com os
    arquivos adicionados ou modificados nos últimos 30 dias em todo o aparelho (MediaStore, até 500,
    mais novos primeiro). Fora: pastas protegidas, ocultas e Android/data e obb. Abrir, compartilhar,
    renomear, mover e apagar funcionam; criar pasta, enviar, colar e buscar ficam ocultos. Precisa do
    acesso a todos os arquivos (API 30+); chave "Mostrar a pasta Recentes" para esconder.
  - Testado no Tab S7+: Recentes, pasta inicial em Recentes, raiz, subpasta e subpasta apagada.
- **Segurança de dados (v0.13.6)**: chave de assinatura copiada para `signing/` (ver "Compilar") e
  apagar uma pasta com protegida dentro passou a ser recusado. Testado no S23: apagar arquivo em
  pasta adicionada pelo caminho (Download); apagar pasta com protegida dentro (recusado, nada
  apagado); a mesma pasta sem a proteção (apagada); arquivo de pasta protegida fora de Recentes
  (presente na MediaStore com data de hoje, ausente da lista).
- **Filtros na Recentes (v0.13.7)**: botão de funil (só em Recentes) com tipo — todos, fotos,
  vídeos, áudio, documentos (tipo do Android + PDF, texto e Office pelo mime) ou APKs — e período
  (7, 30 ou 90 dias). Feito na consulta à MediaStore (`MEDIA_TYPE`/`MIME_TYPE`), salvo nas
  preferências; a linha da lista de pastas mostra o filtro ("Fotos · últimos 7 dias"). Testado no
  Tab S7+: APKs (5 na lista = 5 na MediaStore), fotos em 7 dias (só imagens), documentos (só PDF e
  HTML) e a descrição no painel lateral. v0.13.8: linha divisória entre os dois grupos do menu
  (`MenuCompat.setGroupDividerEnabled`).

### Relatório de desempenho (S23, Android 17, v0.13)

| Medida | Resultado |
|---|---|
| Abertura a frio (`am start -S -W`, 6×, logo após instalar) | 128–275 ms; mediana ~166 ms. As 2 primeiras incluem a compilação pós-instalação; estável ~130–160 ms |
| Memória na tela inicial (PSS) | 77 MB no total: Java 8,5 MB, nativa 12 MB, gráficos 45 MB (texturas da GPU) |
| Rolagem (pasta Camera, ~900 fotos, modo lista; 30 arrastes rápidos, `gfxinfo`) | 3.817 quadros, 0,34% atrasados (13); mediana 7 ms, p90/p95 13 ms, p99 14 ms; nenhum upload lento de bitmap, 1 quadro lento na thread de UI |
| Memória (v0.13 → v0.13.1), mesmo roteiro: abrir Camera, 30 arrastes | ver tabela abaixo |

**Pico de memória reduzido (v0.13.1).** Miniaturas que saíam do cache (teto de 32 MB) seguiam
vivas até o GC rodar, com a cópia delas na GPU: o heap nativo chegava a 77 MB e o `Gfx dev` a
77 MB. Agora cada uma é liberada (`recycle`) ao sair do cache — na hora, se não está em
nenhuma célula, ou quando a última célula que a mostra troca de imagem (contagem por view,
com referências fracas). Isso também solta a textura no driver.

| Momento (PSS, KB) | v0.13 total | v0.13 nativa | v0.13 Gfx dev | v0.13.1 total | v0.13.1 nativa | v0.13.1 Gfx dev |
|---|---|---|---|---|---|---|
| Camera aberta | 86.485 | 14.648 | 2.420 | 88.915 | 15.588 | 2.304 |
| Logo após rolar | 324.945 | 77.148 | 77.012 | **227.477** | **48.716** | **43.916** |
| Parado 15 s | 278.126 | 49.756 | 77.012 | **207.390** | 49.088 | **43.916** |
| Segundo plano 8 s | 193.252 | 49.812 | 57.164 | **125.748** | **15.692** | **40.480** |

O resto dos gráficos é do sistema: ~40 MB de buffers da tela (`EGL mtrack`, fixo) e o cache de
texturas do HWUI/driver (`GL mtrack` ~54 MB), que o Android solta sozinho quando precisa.
Rolagem igual depois da mudança (3.863 quadros, 0,49% atrasados, p99 14 ms).
| Teto da memória de miniaturas | 32 MB (1/8 do heap, com teto) |
| Visualizador | 3 fotos base (≈ 5 MB cada em RGB_565 na tela do S23) + 1 recorte |

### Relatório de tamanho do APK (v0.13: 962.857 bytes, 940 KB)

| Parte | No APK | Descompactado |
|---|---|---|
| Código (`classes.dex`, R8) | 626 KB | 1,26 MB — ~142 KB são do Play Services Auth |
| Recursos (`res/`, 347 arquivos, todos vetoriais/XML) | 149 KB | 264 KB |
| `resources.arsc` (só português) | 142 KB | 142 KB |
| Outros + assinatura | 5 KB | 14 KB |

De 529 KB (fase 0) a 940 KB em 13 fases: Drive (+142 KB de Play Services), bloqueio e
cofre, edição, temas e visualizador. Sem bibliotecas nativas (`lib/` vazio).

## Leitor de documentos (v0.14.0)

Abre dentro do app, só para ler: PDF, `.docx`/`.doc`, `.xlsx`/`.xls`, `.pptx`/`.ppt`,
`.odt`/`.ods`/`.odp`, `.rtf`, `.txt` e afins (json, xml, log, código), `.md`, `.csv`/`.tsv` e
`.html`. Chave em Configurações → Navegação → Arquivos ("Abrir documentos no leitor do Index"); desligada,
volta a abrir no app externo. Barra com Compartilhar e Abrir com.

- **Sem dependências novas.** PDF pelo `PdfRenderer` do Android numa RecyclerView própria
  (`PdfView`): páginas renderizadas sob demanda numa thread só, cache LRU de bitmaps (≤ 1/5 do
  heap), pinça, toque duplo (2,5×) e arraste, re-render nítido ao parar o zoom. Os demais
  formatos são convertidos em HTML simples e mostrados num WebView com JavaScript, rede, arquivos
  e navegação desligados (`loadDataWithBaseURL("about:blank")`, todo texto escapado).
- **Office e LibreOffice novos** são ZIP de XML: lidos na memória (`ZipInputStream`, sem cópia em
  disco — importa para as pastas protegidas) com `XmlPullParser`. Word: títulos, negrito/itálico/
  sublinhado, listas e tabelas. Excel/Calc: todas as abas, strings compartilhadas, datas pelo
  formato da célula, números com vírgula. PowerPoint/Impress: slides na ordem, título em destaque.
- **Office 97–2003** (`.doc`, `.xls`, `.ppt`): leitor próprio do contêiner OLE2 e dos formatos
  binários — tabela de peças do Word (texto e tabelas), registros BIFF8 do Excel (SST, números,
  RK, fórmulas com resultado, datas) e átomos de texto do PowerPoint por slide.
- **Limites** para ficar rápido e não estourar memória: texto até 4 MB, binários até 64 MB, 5.000
  linhas e 200 colunas por planilha, HTML final até 6 milhões de caracteres (aviso de "cortado").
  Arquivo com senha mostra a mensagem certa, sem travar.
- Testado no Tab S7+ com 16 arquivos gerados no Word, Excel e PowerPoint (e escritos à mão): todos
  abrem; tabelas do `.doc` e do `.rtf` como tabela, datas e decimais certos, página HTML com fundo
  branco, PDF com contador de páginas.
- **Correção (mesmo dia):** páginas de PDF ficavam em branco. A lista pré-carrega a próxima página
  e guarda as que saem da tela; quando voltam, não passam de novo pelo `onBindViewHolder`, e a
  imagem pronta só ia para as páginas já na tela. Agora os holders ligados ficam num conjunto e
  `onViewAttachedToWindow` confere a imagem. Testado com um PDF de 20 páginas (tablet) e no S23.
- **Word com formatação (v0.14.1).** `Docx` lê `styles.xml` (estilos com herança `basedOn` e os
  padrões do documento), `numbering.xml` (listas numeradas 1./a./i., marcadores, recuo por nível,
  início redefinido) e as imagens de `word/media/` (PNG, JPEG, GIF, BMP, WebP embutidos como
  `data:`; até 4 MB cada e 10 MB no total; EMF/WMF viram "[imagem]"). Aplica tamanho, cor, realce,
  família (serifa/sem serifa/monoespaçada), negrito, itálico, sublinhado, tachado, sobrescrito,
  maiúsculas e texto oculto; alinhamento, recuos, espaçamento antes/depois, entrelinha e
  sombreamento; tabelas com células mescladas na horizontal e sombreadas; caixas de texto. Ignora
  revisões apagadas e formatação antiga de revisão. O texto normal do documento vira 16 px e o
  resto é proporcional. Mostrado como folha branca com as cores do documento (não segue o tema
  escuro). Cores e fontes são validadas antes de entrar no CSS. `.doc` (97–2003) segue só texto.
  Testado no Tab S7+ com documento gerado no Word (título, títulos coloridos, cores, tamanhos,
  alinhamentos, recuo, listas, tabela sombreada e imagem).
- **v0.14.2.** `DocActivity`: o Index aparece no "Abrir com" de outros apps para os formatos do
  leitor (PDF, Office, LibreOffice, RTF, texto, Markdown, CSV, HTML); tela só do documento, sem o
  resto do app; voltar retorna a quem chamou. `UriSource` lê o arquivo recebido (só leitura).
  `DocFmt`: `.doc` 97–2003 com formatação (folha de estilos STSH, FKPs de CHPX/PAPX, listas
  PlfLst/PlfLfo, tabelas por marca de célula/linha); sem sombreamento de tabela e sem imagens
  ("[imagem]"); se algo falhar, cai no modo só texto. Testado no Tab S7+ (abertura externa de
  .docx e .doc; .doc convertido pelo Word e o de teste antigo).

## Recentes do Drive e pastas Recentes adicionadas (v0.15.0)

- **Recentes do Drive**: pasta virtual (`DriveRecentSource`, raiz -11 fora do banco) com os
  arquivos criados ou modificados no Drive no período, mais novos primeiro (até 500; pastas e
  atalhos de fora). Mesmos filtros da Recentes do aparelho (tipo e 7/30/90 dias, compartilhados);
  em Documentos entram também Documentos, Planilhas e Apresentações Google. Abrir, miniaturas,
  compartilhar, renomear, mover e apagar como nas pastas do Drive.
- Arquivos dentro de pastas protegidas do Drive ficam de fora: o app lista uma vez as subpastas
  das protegidas (guardado por 10 min) e compara com as pastas pai de cada arquivo.
- **Nada vem de fábrica**: as duas Recentes são adicionadas como as outras pastas ("Adicionar pasta
  de" e o "+" de Gerenciar pastas), pedem a permissão ou o login se faltar e saem pelo ⋮ "Remover".
  A chave "Mostrar a pasta Recentes" saiu; quem atualiza mantém as que já via.
- **Posição da lista**: ao voltar de uma imagem, de outro app ou do processo recriado, a pasta
  continua onde estava (e as pastas de cima também).
- Testado no S23.

## Salvar no Index (v0.16.0)

- O Index aparece no menu de compartilhar do Android como **Salvar no Index**, para um ou vários
  arquivos de qualquer tipo (SEND/SEND_MULTIPLE na `MainActivity`).
- Abre a lista de pastas (com o bloqueio do app, se ligado); em qualquer pasta do aparelho ou do
  Drive aparece a barra "N arquivos para salvar" com **Salvar aqui** e **Cancelar** (a mesma barra
  do colar). Salvar usa a cópia de "Enviar arquivos" (`FileOps.upload`): nomes repetidos ganham
  outro nome, nada é sobrescrito. Ao terminar, volta para o app que compartilhou; com erro, mostra o
  motivo e deixa escolher outra pasta. Cancelar ou voltar também retorna ao app de origem.
- Recentes não recebe arquivos (a barra não aparece). Compartilhamento só de texto: avisa e fecha.
- Testado no S23.

## Player de vídeo (v0.17.0)

- `ui/video/VideoFragment` (Media3/ExoPlayer, no `viewer_host` como o visualizador de fotos).
  Arquivo local pelo caminho, SAF pela Uri do documento, Drive pelo link de streaming do app.
- **Gestos**: toque mostra/esconde os controles (somem em 3 s); dois toques à direita/esquerda
  avançam/voltam (10 s por padrão, 5 a 60 s nas Configurações; toques seguidos somam); segurar
  acelera para 2x até soltar. Teclado: espaço, setas, J/K/L, Esc.
- **Linha do tempo com ajuste fino** (`FineSeekBar`): tocar pula; arrastando, quanto mais o dedo sobe
  acima da barra, mais devagar a posição anda (1, 1/2, 1/4, 1/8, 1/16), com busca do quadro exato e
  tempo com décimos.
- **Trilhas**: escolha de áudio (com idioma, canais e codec) e de legenda (com "Desligada").
  Legendas embutidas (SRT, ASS/SSA, VTT, TTML, PGS, VobSub, CC) e externas com o mesmo nome do
  vídeo (`Filme.srt`, `Filme.pt-BR.srt`; .srt/.vtt/.ass/.ssa/.ttml).
- **Formatos**: contêineres MP4/M4V/MOV, MKV, WebM, AVI, FLV, TS/M2TS, 3GP, OGG; vídeo H.264,
  H.265, VP8, VP9, AV1, MPEG-4 Part 2 (pelos decodificadores do aparelho, com fallback para o de
  software); áudio AAC, MP3, Opus, Vorbis, FLAC, PCM, AC-3/E-AC-3. Sem DTS/TrueHD/WMA. Formato não
  suportado: aviso com "Abrir em outro app".
- **Para outro app direto**: WMV/ASF (sem leitor nem decodificador no Android; só com FFmpeg ou
  libVLC, de 3 a 30 MB) e GIF (o visualizador mostraria parado; também sai da sequência de fotos).
- Pausa ao sair da tela, ao bloquear e ao desconectar o fone. Chave "Abrir vídeos no player do
  Index" (ligada). Vídeos da pesquisa também abrem no player.
- APK: +525 KB (1.047 KB para 1.572 KB). Testado no S23.

## Digitalizar documentos (v0.18.0)

- Menu da pasta, "Digitalizar documento": abre o scanner do Google Play services (ML Kit,
  modo completo): bordas e corte automáticos, filtros, limpeza de sombras e manchas, flash, várias
  páginas e importar da galeria. A câmera é dele: o Index não pede permissão de câmera.
- Ao voltar, pergunta como salvar (lembra a escolha): PDF com todas as páginas, imagens JPG (uma
  por página) ou os dois, na pasta aberta (do aparelho ou do Drive), com nomes
  "Digitalização AAAA-MM-DD HH.MM.SS" (" - página N" nas imagens). Usa `FileOps.upload` com nomes.
- R8: regra para manter os `ComponentRegistrar` (criados por reflexão a partir do manifesto); sem
  ela o scanner caía com NullPointerException ao abrir.
- O flash é da tela do Google: liga/desliga, sem intensidade. Ajuste de intensidade exigiria tela de
  câmera própria (Camera2, `FLASH_STRENGTH_LEVEL`, Android 15+).
- APK: +107 KB (1.572 KB para 1.679 KB). Testado no S23.

## Atalhos no ícone e na tela inicial (v0.19.0)

- Segurando o ícone do app: **Recentes**, **Digitalizar** e (v0.20.0) **IndexWeb**
  (`res/xml/shortcuts.xml`, ações próprias tratadas no `onCreate` e depois do desbloqueio).
  Digitalizar reaproveita o fluxo do "Salvar no Index": escolhe a pasta e volta.
- Gerenciar pastas, menu de cada pasta: "Adicionar atalho à tela inicial" (atalho fixado,
  `ShortcutManagerCompat.requestPinShortcut`); abre a pasta por cima da lista. Pasta removida do
  Index: o atalho é desativado com aviso.
- Saiu o botão de alternar grade/lista da barra: a troca é só pela pinça.

## IndexWeb: acesso pelo computador (v0.20.0)

- Menu ⋮ (ou atalho do ícone) → IndexWeb → Ligar. O navegador do PC, na mesma rede, abre
  `http://index.local:8080` (nome fixo, mDNS próprio em `web/MdnsResponder.kt`) ou o endereço com
  números. Só endereços de rede local aparecem (Wi-Fi, roteador do celular, cabo/USB; fora dados
  móveis, VPN, Wi-Fi Direct).
- Servidor HTTP/1.1 próprio (`web/WebServer.kt`, sem biblioteca): keep-alive, Range (vídeo com
  avanço), zip em fluxo, envio por PUT com arquivo temporário. Página única em
  `assets/web/index.html` (modelo 1 escolhido pelo usuário): árvore de pastas, volumes com uso,
  bateria e aparelho, lista ou miniaturas (P/M/G, fotos com EXIF e vídeos), seleção múltipla,
  baixar vários (.zip ou separados), enviar vários ou pasta inteira (3 ao mesmo tempo, arrastar e
  soltar), nova pasta, renomear, mover, copiar, apagar, pesquisa e visualização.
- Segurança: senha definida no app (PBKDF2; 5 erros = 5 min de espera), sessão por cookie
  HttpOnly/SameSite=Strict, cabeçalho próprio em toda ação, Host só IP/localhost/index.local
  (contra DNS rebinding), caminhos presos ao volume, pastas protegidas invisíveis e bloqueadas,
  SVG/HTML nunca abertos inline. Sem criptografia: só redes de confiança (aviso na tela).
- Serviço em primeiro plano (dataSync) com WifiLock e WakeLock; desliga após 30 min sem uso.
- Android 17: a permissão de rede local é obrigatória (sem ela as conexões do PC expiravam).
- Configurações reorganizadas em categorias (Aparência, Navegação e arquivos, Abrir e compartilhar,
  Miniaturas, Google Drive, Segurança, IndexWeb, Dados e sobre); menu da pasta com submenus
  "Adicionar" (nova pasta, enviar, digitalizar) e "Tamanho das miniaturas".
- Compilação: cache de configuração e compilação paralela no `gradle.properties`.
- Testado no S23 (página, nome index.local, Wi-Fi e roteador do celular).

### Página no celular e no tablet (v0.21.0)

- Só CSS e um pouco de JS em `assets/web/index.html`; no computador nada muda.
- Até 1100 px: barra lateral mais estreita. Até 760 px: pastas, armazenamento e aparelho numa
  gaveta (botão ☰), caminho numa linha que rola, busca na largura toda, botões numa faixa que rola,
  lista só com nome e tamanho, painel de envio, visualização e diálogos ajustados à tela.
- Tela de toque: um toque abre, segurar seleciona; com seleção ativa o toque marca/desmarca e a
  barra mostra só as ações da seleção e "✕ Limpar". Alvos maiores, caixas de seleção sempre
  visíveis nas miniaturas, deslizar troca de arquivo na visualização (exceto PDF e texto).
- Testado no navegador em tamanho de celular e tablet; instalado no S23 e no Tab S7+.

### Layouts, aparelho na entrada e desligar de verdade (v0.22.0)

- Seis layouts, escolhidos em "Layout" (ou ⋯ › Trocar layout) e salvos em cada navegador:
  Clássico (o de antes), Galeria (fotos e vídeos por data, filtros por tipo), Colunas (uma
  coluna por pasta e detalhes à direita), Painel duplo (A e B, F5 copia e F6 move para o outro),
  Painel inicial (armazenamento, arquivos por tipo e recentes de todo o aparelho) e Lista com
  comandos (ações na linha, caminho editável). Ctrl+K abre os comandos em qualquer um.
- Novas rotas: `/api/hello` (nome e modelo do aparelho, sem login, para a tela de senha),
  `/api/stats` (contagem e tamanho por tipo e 24 fotos/vídeos recentes, pelo MediaStore, 30 s em
  cache) e `/api/kind` (até 1000 arquivos de um tipo). Pastas protegidas e ocultas ficam de fora.
- Tela de senha mostra o nome do aparelho (o das configurações do Android) e o modelo.
- Desligar fecha todas as conexões abertas, não só a porta: antes, um navegador já conectado
  seguia respondendo por até 60 s e transferências continuavam. Testado no Tab S7+: a conexão
  caiu na hora e uma nova foi recusada. A página mostra "Sem conexão" (confere a cada 20 s).
- Permissão de notificação (Android 13+), que faltava: sem ela a notificação do IndexWeb, com o
  botão Desligar, nunca aparecia.
- Testado no navegador em computador e celular; instalado no S23 e no Tab S7+.

## Botões flutuantes (v0.23.0)

- Três botões no canto de baixo da lista de pastas e de dentro das pastas: Recentes, Downloads
  e IndexWeb. Ficam 52 dp longe da borda direita, fora da faixa da rolagem rápida. Somem na
  seleção, com a barra de colar aberta, nas pastas protegidas e nas outras telas. As listas
  ganharam 88 dp de espaço no fim para o último item não ficar atrás deles.
- Ícones próprios: relógio com seta voltando (Recentes), seta descendo para a bandeja
  (Downloads) e computador com sinal de Wi-Fi (IndexWeb). Desligado: botão claro e sinal
  cortado por um traço; ligado: botão cheio na cor do tema e sinal aceso.
- Recentes abre a pasta Recentes do aparelho (como o atalho do ícone).
- Downloads abre a pasta Download; se não estiver na lista de pastas, adiciona e avisa (pede
  "Acesso a todos os arquivos" se faltar). Protegida: avisa e não abre.
- IndexWeb é uma chave: tocar liga ou desliga; se faltar senha ou permissão, abre a tela do
  IndexWeb, que pede o que falta e liga. Segurar abre a tela do IndexWeb.
- Testado no Tab S7+ (chave ligou e desligou o servidor; segurar abriu a tela); instalado no S23.

### Senha salva, instalar como app e Locais (v0.24.0)

- Tela de senha com "Salvar senha neste navegador": o cookie passa a durar 90 dias e vale
  depois de religar o IndexWeb. O celular guarda só o SHA-256 do token (preferências
  `web_remember`); Sair apaga aquele, trocar a senha no app apaga todos, redefinir o app também.
- "Instalar como app": `/manifest.webmanifest` e `/icon-192.png`/`/icon-512.png` (o ícone do app
  desenhado em PNG), sem login. No primeiro login a página pergunta se quer instalar; depois fica
  em ⋯, no menu lateral e no Ctrl+K. Em `http` da rede local o navegador não oferece o botão de
  instalar sozinho, então a página mostra os passos do menu de cada navegador (Chrome, Edge,
  Firefox, Android, Samsung Internet, iPhone/iPad). Aberta pelo IP, sugere instalar por
  `index.local`.
- A seção "Armazenamento" da coluna de pastas virou "Locais": Recentes (300 arquivos mais novos
  do aparelho, pelo MediaStore, `/api/kind?k=recent`), DCIM/Camera, Downloads, Memória interna e
  Cartão SD (com espaço livre). A árvore continua embaixo.
- Testado no navegador; no Tab S7+, manifesto e ícone respondem sem login e o resto pede login.
  Instalado no S23 e no Tab S7+.

### Nova aba, aviso ao sair, falhas explicadas e desligar ao fim (v0.25.0)

- EF10: abrir um arquivo leva a uma nova aba do navegador (`window.open`, `noopener`); o que o
  navegador não mostra é baixado. Sai o visualizador dentro da página.
- EF12: com envio, cópia, movimentação ou exclusão em andamento, o navegador pede confirmação
  ao fechar, recarregar ou voltar (`beforeunload`); Sair e Desligar também confirmam. O
  desligamento por inatividade já não ocorria durante transferências.
- EF13: o painel de envio mostra "Enviados X de Y", o motivo de cada falha (sem espaço, conexão
  perdida, nome inválido, sessão expirada) e "Tentar de novo (n)" só com os que falharam.
  `/api/transfer` e `/api/delete` devolvem `why` (motivo por item); a página lista os que falharam
  e tenta de novo só esses.
- EF14 (em parte): envio concluído sem falhas pergunta "Nada" ou "Desligar o IndexWeb" (dá para
  não perguntar mais). `POST /api/shutdown` (com login) responde e para o serviço; também em ⋯ e
  no Ctrl+K. Desligar o computador precisa de um programa auxiliar no PC e ficou para depois.
- Testado no navegador com servidor de teste; instalado no Tab S7+.

## Dependências

| Dependência | Motivo |
|---|---|
| `androidx.appcompat:appcompat` 1.7.1 | Tema DayNight e `AppCompatDelegate.setDefaultNightMode` em API 26+. Traz `fragment`, `activity` e `core` transitivamente (Activity única com Fragments, seletor SAF via Activity Result). |
| `androidx.recyclerview:recyclerview` 1.4.0 | Grade/lista com reciclagem de views e `DiffUtil` (exigidos pela especificação). |
| `com.google.android.gms:play-services-auth` 22.0.0 | OAuth do Drive (`AuthorizationClient`). +142 KB no APK após R8. |
| `com.google.android.gms:play-services-mlkit-document-scanner` 16.0.0 | Digitalizar documentos (v0.18.0): tela, bordas, filtros e PDF vêm do Play services; cliente fino, +107 KB. |
| `androidx.media3:media3-exoplayer` e `media3-ui` 1.8.0 | Player de vídeo (v0.17.0): MKV/AVI/FLV, legendas embutidas e externas, troca de trilha, o que o `MediaPlayer` do Android não cobre. Da `media3-ui` só `SubtitleView` e `AspectRatioFrameLayout` (controles próprios). +525 KB após R8. |

Nenhuma outra (v0.19.0 a v0.25.0 não trouxeram nenhuma: o servidor do IndexWeb e o mDNS são
próprios). Kotlin vem embutido no AGP 9.

## Histórico do APK release

| Versão | Tamanho | Abertura a frio (SM-S911B, Android 17) |
|---|---|---|
| v0.1-fase0 | 541.896 bytes (529 KB) | 110–151 ms, mediana ~123 ms (8× `am start -S -W`) |
| v0.2-fase1 | 656.600 bytes (641 KB) | — |
| v0.3-fase2 | 666.568 bytes (651 KB) | — |
| v0.4-fase3 | 679.685 bytes (664 KB) | — |
| v0.5-fase5 | 821.766 bytes (803 KB) | 113–138 ms (5× após force-stop) |
| v0.5.1-fase5 (streaming) | 826.166 bytes (807 KB) | — |
| v0.5.3-fase5 (download antecipado no streaming) | 828.030 bytes (809 KB) | — |
| v0.5.4-fase5 (libera memória na pausa e em segundo plano) | 828.066 bytes (809 KB) | — |
| v0.6-fase6 (bloqueio do app) | 847.734 bytes (828 KB) | — |
| v0.7-fase7 (edição de arquivos) | 869.310 bytes (849 KB) | — |
| v0.7.1-fase7 (transferências em segundo plano) | 870.878 bytes (850 KB) | — |
| v0.8-fase8 (pastas protegidas) | 892.814 bytes (872 KB) | — |
| v0.9-fase9 (seleção aleatória) | 903.415 bytes (882 KB) | — |
| v0.10-fase10 (pré-carga de miniaturas) | 914.063 bytes (893 KB) | — |
| v0.10.1-fase10 (MB na RAM no contador) | 914.271 bytes (893 KB) | — |
| v0.10.2-fase10 (aceleração da rolagem mais suave) | 914.259 bytes (893 KB) | — |
| v0.11-fase11 (temas) | 928.240 bytes (906 KB) | — |
| v0.11.1-fase11 (seleção por arraste) | 930.132 bytes (908 KB) | — |
| v0.12-fase12 (tablet, teclado e mouse) | 934.264 bytes (912 KB) | — |
| v0.12.1-fase12 (pinça: da lista a uma por tela) | 934.956 bytes (913 KB) | — |
| v0.12.2-fase12 (seleção no sorteio/pesquisa, Abrir com) | 937.521 bytes (916 KB) | — |
| v0.12.3-fase12 (lista própria do Abrir com, players) | 940.557 bytes (918 KB) | — |
| v0.12.4-fase12 (visualizador de fotos, compartilhar, listas de apps) | 962.017 bytes (939 KB) | — |
| v0.13-fase13 (polimento, relatórios) | 962.857 bytes (940 KB) | 128–275 ms, mediana ~166 ms (6×, logo após instalar) |
| v0.13.1-fase13 (miniaturas liberadas ao sair do cache) | 963.089 bytes (941 KB) | — |
| v0.13.2-fase13 (ícone novo, configurações reorganizadas, seção Sobre) | 965.205 bytes (943 KB) | — |
| v0.13.3-fase13 (pastas bloqueadas: Download, raízes) | 969.853 bytes (947 KB) | — |
| v0.13.4-fase13 (puxar para atualizar, pastas em dia) | 972.373 bytes (950 KB) | — |
| v0.13.5-fase13 (pasta inicial, Recentes) | 978.325 bytes (955 KB) | — |
| v0.13.6-fase13 (chave no projeto, apagar recusa pasta com protegida) | 978.665 bytes (956 KB) | — |
| v0.13.7-fase13 (filtros na Recentes) | 981.493 bytes (958 KB) | — |
| v0.13.8-fase13 (divisória no filtro da Recentes) | 981.837 bytes (959 KB) | — |
| v0.14.0-fase13 (leitor de documentos) | 1.021.089 bytes (997 KB) | — |
| v0.14.1-fase13 (Word com formatação) | 1.031.941 bytes (1.008 KB) | — |
| v0.14.2-fase13 (abrir de outros apps, .doc formatado) | 1.042.265 bytes (1.018 KB) | — |
| v0.14.3-fase13 (sem nome do autor no Sobre) | 1.042.165 bytes (1.018 KB) | — |
| v0.15.0-fase13 (Recentes do Drive, Recentes adicionadas, posição da lista) | 1.045.357 bytes (1.021 KB) | — |
| v0.16.0-fase13 (Salvar no Index pelo compartilhar) | 1.046.909 bytes (1.022 KB) | — |
| v0.17.0-fase13 (player de vídeo com Media3) | 1.572.249 bytes (1.535 KB) | — |
| v0.18.0-fase13 (digitalizar documentos) | 1.679.353 bytes (1.640 KB) | — |
| v0.19.0-fase13 (atalhos no ícone e de pastas; sem snapshot próprio, entregue com a v0.20.0) | 1.686.881 bytes (1.647 KB) | — |
| v0.20.0-fase13 (IndexWeb, configurações em categorias) | 1.723.816 bytes (1.683 KB) | — |
| v0.21.0-fase13 (IndexWeb no celular e no tablet) | 1.725.084 bytes (1.684 KB) | — |
| v0.22.0-fase13 (IndexWeb com 6 layouts, desligar fecha conexões) | 1.742.872 bytes (1.702 KB) | — |
| v0.23.0-fase13 (botões flutuantes Recentes, Downloads e IndexWeb) | 1.749.404 bytes (1.708 KB) | — |
| v0.24.0-fase13 (IndexWeb: senha salva, instalar como app, Locais) | 1.752.336 bytes (1.711 KB) | — |
| v0.25.0-fase13 (IndexWeb: nova aba, aviso ao sair, falhas explicadas, desligar ao fim) | 1.753.576 bytes (1.712 KB) | — |
