// DuelSira uctan uca test: gercek Paper 1.20.1 sunucusu + 3 mineflayer botu
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');

const SERVER_DIR = path.join(__dirname, '..', 'server');
const results = [];
const log = (...a) => console.log('[TEST]', ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function check(cond, name) {
  results.push({ name, ok: !!cond });
  log(cond ? 'PASS' : 'FAIL', name);
}

// ------------------------------------------------------------------ sunucu
const srv = spawn('java', ['-Xms1G', '-Xmx2G', '-Dfile.encoding=UTF-8', '-jar', 'paper.jar', '--nogui'], { cwd: SERVER_DIR });
const consoleLines = [];
const consoleWaiters = [];
function onServerData(d) {
  for (const raw of d.toString('utf8').split('\n')) {
    const line = raw.replace(/\x1b\[[0-9;]*m/g, '').trimEnd();
    if (!line) continue;
    process.stdout.write('[SRV] ' + line + '\n');
    consoleLines.push(line);
    for (const w of [...consoleWaiters]) {
      if (w.re.test(line)) {
        consoleWaiters.splice(consoleWaiters.indexOf(w), 1);
        w.resolve(line);
      }
    }
  }
}
srv.stdout.on('data', onServerData);
srv.stderr.on('data', onServerData);
let serverExited = false;
srv.on('exit', () => { serverExited = true; });

function waitConsole(re, ms = 15000) {
  return new Promise((resolve) => {
    const w = { re, resolve };
    consoleWaiters.push(w);
    setTimeout(() => {
      const i = consoleWaiters.indexOf(w);
      if (i >= 0) { consoleWaiters.splice(i, 1); resolve(null); }
    }, ms);
  });
}
function cmd(c) { log('console>', c); srv.stdin.write(c + '\n'); }
async function cmdExpect(c, re, ms = 10000) { const p = waitConsole(re, ms); cmd(c); return p; }

// ------------------------------------------------------------------ botlar
function makeBot(name) {
  return new Promise((resolve, reject) => {
    const b = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.20.1', auth: 'offline' });
    b.waiters = [];
    b.on('messagestr', (m, pos) => {
      if (pos === 'game_info') return; // action bar
      console.log(`[${name}] ${m}`);
      for (const w of [...b.waiters]) {
        if (w.re.test(m)) { b.waiters.splice(b.waiters.indexOf(w), 1); w.resolve(m); }
      }
    });
    b.on('kicked', (r) => console.log(`[${name}] KICKED`, r));
    b.on('error', (e) => console.log(`[${name}] ERROR`, e && e.message));
    b.once('spawn', () => {
      // Botlar yerinde dursun: fizik kapali (geri itme/isinlanma sonrasi gecersiz paket gondermesinler)
      b.physicsEnabled = false;
      resolve(b);
    });
    setTimeout(() => reject(new Error(name + ' baglanamadi')), 60000);
  });
}
function waitChat(b, re, ms = 8000) {
  return new Promise((resolve) => {
    const w = { re, resolve };
    b.waiters.push(w);
    setTimeout(() => {
      const i = b.waiters.indexOf(w);
      if (i >= 0) { b.waiters.splice(i, 1); resolve(null); }
    }, ms);
  });
}
function waitWindow(b, ms = 5000) {
  return new Promise((resolve) => {
    const h = (w) => resolve(w);
    b.once('windowOpen', h);
    setTimeout(() => { b.removeListener('windowOpen', h); resolve(null); }, ms);
  });
}
const npcOf = (b) => b.nearestEntity((e) => e.name === 'villager');
function useNpc(b) {
  const e = npcOf(b);
  if (!e) { log('NPC bulunamadi (' + b.username + ')'); return false; }
  b.activateEntity(e);
  return true;
}
const inArena = (b) => {
  const p = b.entity.position;
  return p.x >= 100 && p.x < 121 && p.z >= 100 && p.z < 121;
};
async function hasStrength(name) {
  const r = await cmdExpect(`execute if entity @a[name=${name},nbt={ActiveEffects:[{Id:5}]}]`, /Test (passed|failed)/);
  return !!(r && /passed/.test(r));
}
async function click(b, slot, mode = 0) {
  try { await b.clickWindow(slot, 0, mode); } catch (e) { /* iptal edilen tiklama */ }
}

// ------------------------------------------------------------------ senaryo
async function main() {
  const done = await waitConsole(/Done \(/, 300000);
  check(done, 'Sunucu acildi');
  check(consoleLines.some((l) => /DuelSira v1\.2\.0 aktif/.test(l)), 'DuelSira aktif oldu');
  check(!consoleLines.some((l) => /Error occurred while enabling DuelSira/.test(l)), 'Enable hatasi yok');

  check(await cmdExpect('rg flag -w world __global__ pvp deny', /pvp/i), 'WorldGuard global pvp=deny');

  const A = await makeBot('BotA');
  const B = await makeBot('BotB');
  const C = await makeBot('BotC');
  cmd('op BotA');
  cmd('gamerule sendCommandFeedback true');
  await sleep(1500);

  // --- tab complete + alias
  let tc = [];
  try { tc = await A.tabComplete('/duelsira ekle zomb', true, false, 5000); } catch (e) { log('tabComplete hata', e.message); }
  const tcNames = (tc || []).map((m) => (typeof m === 'string' ? m : m.match));
  check(tcNames.includes('zombie') || tcNames.includes('zombie_villager'), 'Mob tab-complete (' + tcNames.join(',') + ')');
  const helpAlias = waitChat(A, /DUEL SIRASI/);
  A.chat('/duelsıra');
  check(await helpAlias, '/duelsıra alias calisiyor');

  // --- Komutla siraya girilemez
  let tc1 = [];
  try { tc1 = await A.tabComplete('/duelsira ', true, false, 5000); } catch (e) { log('tabComplete hata', e.message); }
  const tc1Names = (tc1 || []).map((m) => (typeof m === 'string' ? m : m.match));
  check(!tc1Names.some((n) => /gir|katil|katıl|join/.test(n)), 'Siraya girme komutu yok (' + tc1Names.join(',') + ')');
  check(tc1Names.includes('kaldır'), 'Tab: kaldır');
  const noJoin = waitChat(A, /Duel sırasına girdin/, 2000);
  A.chat('/duelsira gir');
  check(!(await noJoin), '/duelsira gir ile siraya girilemedi');

  // --- NPC
  cmd('tp BotA 0 -60 0 -90 0');
  await sleep(1500);
  const npcMsg = waitChat(A, /NPC'si oluşturuldu/);
  A.chat('/duelsira ekle villager');
  check(await npcMsg, 'NPC olusturuldu');
  // ikinci NPC (kaldır tab testi icin)
  cmd('tp BotA -6 -60 -6 0 0');
  await sleep(800);
  const npcMsg2 = waitChat(A, /NPC'si oluşturuldu: npc2/);
  A.chat('/duelsira ekle cow');
  check(await npcMsg2, 'Ikinci NPC olusturuldu');
  let tc2 = [];
  try { tc2 = await A.tabComplete('/duelsira kaldır ', true, false, 5000); } catch (e) { log('tabComplete hata', e.message); }
  const tc2Names = (tc2 || []).map((m) => (typeof m === 'string' ? m : m.match));
  check(tc2Names.includes('npc1') && tc2Names.includes('npc2'), 'Tab: kaldır tum NPCleri listeliyor (' + tc2Names.join(',') + ')');
  const rm2 = waitChat(A, /NPC kaldırıldı: npc2/);
  A.chat('/duelsira kaldır npc2');
  check(await rm2, '/duelsira kaldır npc2');
  await sleep(800);
  check(!A.nearestEntity((e) => e.name === 'cow'), 'npc2 dunyadan silindi');
  cmd('tp BotA 0 -60 0 -90 0');
  await sleep(1000);
  await sleep(1000);
  let npc = npcOf(A);
  check(npc && Math.abs(npc.position.x - 0.5) < 0.01 && Math.abs(npc.position.z - 0.5) < 0.01, 'NPC blok ortasinda');
  const npcId = npc && npc.id;

  // --- arena
  cmd('tp BotA 110 -60 110');
  await sleep(2500);
  check(await cmdExpect('setblock 115 -61 115 minecraft:diamond_block', /Changed the block|Could not set/), 'Arena isaret blogu');
  check(await cmdExpect('setblock 108 -60 110 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",Count:1b}]}', /Changed the block/), 'Arenaya elmasli sandik');
  const smartReady = waitConsole(/Arena 'test1' akilli sifirlama hazir/, 20000);
  const p1 = waitChat(A, /position set|pozisyon/i);
  A.chat('//pos1 100,-61,100');
  await p1;
  const p2 = waitChat(A, /position set|pozisyon/i);
  A.chat('//pos2 120,-50,120');
  await p2;
  const created = waitChat(A, /oluşturuldu ve şematiği/);
  A.chat('/duel olustur test1');
  check(await created, 'Arena olusturuldu');
  check(await smartReady, 'Akilli sifirlama tablosu hazirlandi');
  cmd('tp BotA 109.5 -60 110.5 -90 0');
  await sleep(800);
  const s1 = waitChat(A, /spawn 1 ayarlandı/);
  A.chat('/duel arena test1 setspawn1');
  check(await s1, 'Spawn1');
  cmd('tp BotA 111.5 -60 110.5 90 0');
  await sleep(800);
  const s2 = waitChat(A, /spawn 2 ayarlandı/);
  A.chat('/duel arena test1 setspawn2');
  check(await s2, 'Spawn2');
  await sleep(1500);
  check(fs.existsSync(path.join(SERVER_DIR, 'plugins/DuelSira/arenas/test1.schem')), 'test1.schem kaydedildi');

  cmd('tp BotA 2 -60 2');
  cmd('tp BotB 2 -60 -1');
  cmd('tp BotC -1 -60 2');
  await sleep(2500);

  // --- siraya giris (ticaret menusu acilmamali)
  let tradeOpened = false;
  const tradeListener = () => { tradeOpened = true; };
  A.on('windowOpen', tradeListener);
  const jA = waitChat(A, /Duel sırasına girdin/);
  useNpc(A);
  check(await jA, 'A siraya girdi');
  await sleep(800);
  A.removeListener('windowOpen', tradeListener);
  check(!tradeOpened, 'NPC ticaret menusu acilmadi');

  // --- A-B eslesme ve red
  let mA = waitChat(A, /BotB oyuncusuyla eşleştin/);
  let mB = waitChat(B, /BotA oyuncusuyla eşleştin/);
  useNpc(B);
  check(await mA && await mB, 'A-B eslesti, sohbette soruldu');
  const dA = waitChat(A, /BotB eşleşmeyi reddetti/);
  const rqA = waitChat(A, /Tekrar duel sırasının başına/);
  const dB = waitChat(B, /reddettin ve duel sırasından çıktın/);
  B.chat('/duelsira hayir');
  check(await dA && await rqA, 'Reddedince karsi taraf siraya geri alindi');
  check(await dB, 'Reddeden siradan cikti');

  // --- red engeli: B tekrar girer, A ile eslesmemeli
  await sleep(1200); // NPC tiklama bekleme suresi
  const jB = waitChat(B, /Duel sırasına girdin/);
  useNpc(B);
  check(await jB, 'B tekrar siraya girdi');
  check(!(await waitChat(A, /eşleştin/, 3500)), 'Reddeden ayni oyuncuyla tekrar eslesmedi');

  // --- C girer -> A-C eslesir (sohbette envanter butonu YOK)
  await sleep(800);
  mA = waitChat(A, /BotC oyuncusuyla eşleştin/);
  const mC = waitChat(C, /BotA oyuncusuyla eşleştin/);
  const btnLine = waitChat(A, /\[EVET\]/);
  useNpc(C);
  check(await mA && await mC, 'A-C eslesti (B beklemede)');
  const bl = await btnLine;
  check(bl && /\[HAYIR\]/.test(bl) && !/ENVANTER/i.test(bl), 'Sohbette sadece [EVET] [HAYIR] var (' + bl + ')');
  check(!(await waitChat(B, /eşleştin/, 500)), 'B bu sirada eslesmedi');
  const noInv = waitChat(A, /Kullanım/);
  A.chat('/duelsira envanter');
  check(await noInv, '/duelsira envanter kaldirildi');

  // --- kabul -> duel
  const st = waitChat(A, /Duel başlıyor/);
  A.chat('/duelsira evet');
  await sleep(300);
  C.chat('/duelsira evet');
  check(await st, 'Iki taraf kabul edince duel basladi');
  await sleep(1200);
  check(inArena(A) && inArena(C), 'Oyuncular arenaya isinlandi');
  check(await hasStrength('BotA') && await hasStrength('BotC'), 'Duel sirasi duellosunda Kuvvet I verildi');
  const helpBlocked = waitChat(C, /komut kullanamazsın/);
  C.chat('/help');
  check(await helpBlocked, 'Duelde komut engeli');
  await sleep(3000); // geri sayim 3 sn

  const hp0 = C.health;
  cmd('damage BotC 4 minecraft:player_attack by BotA');
  await sleep(1500);
  check(C.health < hp0, 'Rakipler birbirine vurabiliyor (WG pvp deny olsa bile) hp ' + hp0 + '->' + C.health);

  // Sandiktan elmasi al (NBT degisikligi olayla takip edilmeli)
  const chestWin = waitWindow(A);
  try { await A.activateBlock(A.blockAt(new Vec3(108, -60, 110))); } catch (e) { log('activateBlock', e.message); }
  const cw = await chestWin;
  check(cw, 'Arenadaki sandik acildi');
  if (cw) {
    await click(A, 0, 1);
    await sleep(500);
    A.closeWindow(cw);
  }
  await sleep(500);
  check(A.inventory.count(A.registry.itemsByName.diamond.id) === 1, 'Sandiktan elmas alindi');

  check(await cmdExpect('setblock 115 -61 115 minecraft:air', /Changed the block/), 'Arena bozuldu (1)');
  check(await cmdExpect('setblock 116 -60 116 minecraft:gold_block', /Changed the block/), 'Arena bozuldu (2)');

  const partial1 = waitConsole(/Arena 'test1': \d+\/\d+ bolum yeniden yuklendi/, 30000);
  const won = waitChat(A, /Duelloyu kazandın/);
  const lost = waitChat(C, /Duelloyu kaybettin/);
  cmd('kill BotC');
  check(await won, 'Kazanan mesaji');
  check(await lost, 'Kaybeden mesaji');
  await sleep(6500);
  check(!inArena(A), 'Kazanan arenadan cikarildi');
  check(!inArena(C), 'Kaybeden arenadan cikarildi');
  let r = await cmdExpect('execute if block 115 -61 115 minecraft:diamond_block', /Test (passed|failed)/);
  check(r && /passed/.test(r), 'Arena schem ile geri yuklendi (kirilan blok)');
  r = await cmdExpect('execute if block 116 -60 116 minecraft:air', /Test (passed|failed)/);
  check(r && /passed/.test(r), 'Arena schem ile geri yuklendi (konulan blok)');
  r = await cmdExpect('execute if entity @e[type=minecraft:item,x=100,y=-64,z=100,dx=21,dy=20,dz=21]', /Test (passed|failed)/);
  check(r && /failed/.test(r), 'Arenadaki yere dusen esyalar temizlendi');
  const pl1 = await partial1;
  check(pl1 && /2\/4 bolum/.test(pl1), 'Sadece degisen bolumler yapistirildi (sandik NBT + bloklar) (' + pl1 + ')');
  r = await cmdExpect('execute if data block 108 -60 110 Items[{id:"minecraft:diamond"}]', /Test (passed|failed)/);
  check(r && /passed/.test(r), 'Sandik icerigi geri yuklendi (NBT)');

  // --- zaman asimi: B (beklemede) + C
  cmd('tp BotC -1 -60 2');
  await sleep(2000);
  const mB2 = waitChat(B, /BotC oyuncusuyla eşleştin/);
  const mC2 = waitChat(C, /BotB oyuncusuyla eşleştin/);
  useNpc(C);
  check(await mB2 && await mC2, 'B-C eslesti');
  const tB = waitChat(B, /yanıt vermediğin için duel sırasından çıkarıldın/, 9000);
  const tC = waitChat(C, /yanıt vermediğin için duel sırasından çıkarıldın/, 9000);
  check(await tB && await tC, '15 sn (testte 5) icinde yanit vermeyen iki taraf siradan cikti');

  // --- bir taraf kabul, digeri yanitsiz
  cmd('tp BotA 2 -60 2');
  await sleep(1500);
  const jA2 = waitChat(A, /Duel sırasına girdin/);
  useNpc(A);
  check(await jA2, 'A tekrar siraya girdi');
  const mA3 = waitChat(A, /BotB oyuncusuyla eşleştin/);
  useNpc(B);
  check(await mA3, 'A-B eslesti');
  A.chat('/duelsira evet');
  const toA = waitChat(A, /Rakibin süresinde yanıt vermedi/, 9000);
  const rqA2 = waitChat(A, /Tekrar duel sırasının başına/, 9000);
  const toB = waitChat(B, /yanıt vermediğin için/, 9000);
  check(await toA && await rqA2, 'Kabul eden siraya geri alindi');
  check(await toB, 'Yanit vermeyen siradan cikti');

  // --- PvP acik bolge -> siradan atilma
  const kick = waitChat(A, /PvP açık bir bölgeye girdiğin/, 6000);
  cmd('rg flag -w world __global__ pvp allow');
  check(await kick, 'PvP acik bolgede siradan atildi');
  const deny = waitChat(A, /PvP kapalı bir bölgede olmalısın/);
  useNpc(A);
  check(await deny, 'PvP acik bolgede siraya girilemiyor');
  cmd('rg flag -w world __global__ pvp deny');
  await sleep(1500);

  // --- normal duel (GUI) + arenalar dolu uyarisi
  const gw = waitWindow(A);
  A.chat('/duel BotB');
  const g = await gw;
  check(g, 'Duel gonderme menusu acildi');
  check(g && g.slots[15] && g.slots[15].name === 'gray_dye', 'Menude Kuvvet I secenegi (varsayilan kapali)');
  check(g && !g.slots[22] && !g.slots[12], 'Esya dusme ve kural secenekleri kaldirildi');
  const rB = waitChat(B, /BotA sana duel teklifi gönderdi/);
  const rInfo = waitChat(B, /Kuvvet I: Kapalı/);
  if (g) await click(A, 0, 0);
  check(await rB, 'Normal duel istegi gitti');
  check(await rInfo, 'Istekte Kuvvet I: Kapali yaziyor');
  const sB = waitChat(B, /Duel başlıyor/);
  B.chat('/duel kabul BotA');
  check(await sB, 'Normal duel basladi');
  await sleep(800);
  check(!(await hasStrength('BotB')), 'Kuvvet kapaliyken verilmedi');
  cmd('tp BotC -1 -60 2');
  await sleep(1500);
  const full = waitChat(C, /Tüm arenalar şu an dolu/);
  useNpc(C);
  check(await full, 'Arenalar dolu uyarisi');
  await sleep(3000);
  cmd('setblock 115 -61 115 minecraft:air');
  const wonB = waitChat(B, /Duelloyu kazandın/);
  cmd('kill BotA');
  check(await wonB, 'Normal duel bitti');
  await sleep(6500);
  check(!inArena(B) && !inArena(A), 'Normal duel sonrasi arenadan cikildi');
  r = await cmdExpect('execute if block 115 -61 115 minecraft:diamond_block', /Test (passed|failed)/);
  check(r && /passed/.test(r), 'Normal duel sonrasi arena geri yuklendi');

  // --- Kitli duel: envanter takasi ve geri yukleme
  await sleep(1500);
  cmd('clear BotA');
  cmd('give BotA minecraft:iron_sword 1');
  await sleep(800);
  const kc = waitChat(A, /Kit testkit envanterinden oluşturuldu/);
  A.chat('/duel kit olustur testkit');
  check(await kc, 'Kit olusturuldu');
  cmd('clear BotA');
  cmd('give BotA minecraft:apple 2');
  cmd('clear BotB');
  cmd('give BotB minecraft:gold_ingot 3');
  await sleep(1200);
  const swordId = A.registry.itemsByName.iron_sword.id;
  const goldId = A.registry.itemsByName.gold_ingot.id;
  const appleId = A.registry.itemsByName.apple.id;
  let kw = waitWindow(A);
  A.chat('/duel BotB');
  let k1 = await kw;
  check(k1, 'Kitli duel: gonderme menusu');
  kw = waitWindow(A);
  await click(A, 13, 0);
  const k2 = await kw;
  check(k2 && /Kit/.test(JSON.stringify(k2.title)), 'Kit secim menusu acildi');
  kw = waitWindow(A);
  await click(A, 1, 0);
  const k3 = await kw;
  check(k3, 'Kit secildi, ana menuye donuldu');
  kw = waitWindow(A);
  await click(A, 15, 0);
  const k4 = await kw;
  check(k4 && k4.slots[15] && k4.slots[15].name === 'blaze_powder', 'Kuvvet I acildi');
  const kreq = waitChat(B, /BotA sana duel teklifi gönderdi/);
  const kInfo = waitChat(B, /Kuvvet I: Açık/);
  await click(A, 0, 0);
  check(await kreq, 'Kitli duel istegi gitti');
  check(await kInfo, 'Istekte Kuvvet I: Acik yaziyor');
  const kst = waitChat(B, /Duel başlıyor/);
  B.chat('/duel kabul BotA');
  check(await kst, 'Kitli duel basladi');
  await sleep(1500);
  check(B.inventory.count(swordId) === 1 && B.inventory.count(goldId) === 0, 'Kit verildi (B kendi esyalari yerine kit)');
  check(A.inventory.count(swordId) === 1 && A.inventory.count(appleId) === 0, 'Kit verildi (A)');
  check(await hasStrength('BotA') && await hasStrength('BotB'), 'Manuel duelde Kuvvet I acikken verildi');
  cmd('effect clear BotA');
  await sleep(600);
  check(await hasStrength('BotA'), 'Kuvvet I silinince (sut/totem) geri verildi');
  check(fs.readdirSync(path.join(SERVER_DIR, 'plugins/DuelSira/states')).length === 2, 'Envanter yedegi diske yazildi');
  await sleep(2500);
  const noChange = waitConsole(/Arena 'test1': degisiklik yok, yapistirma atlandi/, 30000);
  const kwon = waitChat(A, /Duelloyu kazandın/);
  cmd('kill BotB');
  check(await kwon, 'Kitli duel bitti');
  await sleep(4500);
  check(B.inventory.count(goldId) === 3 && B.inventory.count(swordId) === 0, 'Kaybedenin eski envanteri geri geldi');
  check(A.inventory.count(appleId) === 2 && A.inventory.count(swordId) === 0, 'Kazananin eski envanteri geri geldi');
  check(fs.readdirSync(path.join(SERVER_DIR, 'plugins/DuelSira/states')).length === 0, 'Envanter yedekleri temizlendi');
  check(!inArena(A) && !inArena(B), 'Kitli duel sonrasi arenadan cikildi');
  check(!(await hasStrength('BotA')) && !(await hasStrength('BotB')), 'Duel bitince Kuvvet I kaldirildi');

  // --- Sira Kuvvet I ayari sadece admin
  const nb = waitChat(B, /yetkin yok/);
  B.chat('/duelsira kuvvet kapat');
  check(await nb, 'Admin olmayan sira kuvvetini degistiremez');
  const ka = waitChat(A, /Kuvvet I ayarlandı: Kapalı/);
  A.chat('/duelsira kuvvet kapat');
  check(await ka, 'Admin sira kuvvetini kapatti');
  const ko = waitChat(A, /Kuvvet I ayarlandı: Açık/);
  A.chat('/duelsira kuvvet aç');
  check(await ko, 'Admin sira kuvvetini acti');
  const nc = await noChange;
  check(nc, 'Blok degismeyen duelde schem yapistirilmadi (' + nc + ')');

  // --- Zorla tam sifirlama komutu
  const fullMsg = waitChat(A, /tam sıfırlama için sıraya alındı/);
  const fullLog = waitConsole(/Arena 'test1': tam yapistirma/, 15000);
  A.chat('/duel arena test1 sifirla');
  check(await fullMsg, '/duel arena test1 sifirla');
  check(await fullLog, 'Zorla tam yapistirma yapildi');

  // --- NPC korumasi
  cmd('kill @e[type=minecraft:villager]');
  await sleep(2000);
  npc = npcOf(C);
  check(npc, 'NPC /kill ile olmedi');
  cmd('tp BotA 3 -60 3');
  await sleep(1500);
  const rm = waitChat(A, /NPC kaldırıldı/);
  A.chat('/duelsira kaldır npc1');
  check(await rm, 'NPC kaldirildi');
  await sleep(1000);
  check(!npcOf(A), 'NPC dunyadan silindi');

  check(await cmdExpect('duel reload', /yeniden y/), '/duel reload');

  A.quit(); B.quit(); C.quit();
  await sleep(1500);
  cmd('stop');
  for (let i = 0; i < 60 && !serverExited; i++) await sleep(1000);

  const errs = consoleLines.filter((l) => /(ERROR|SEVERE|Exception|Could not pass event)/.test(l) && /duelsira|DuelSira/i.test(l));
  errs.forEach((l) => log('HATA SATIRI:', l));
  check(errs.length === 0, 'Konsolda DuelSira hatasi yok');

  const failed = results.filter((x) => !x.ok);
  log('====================================');
  log(`SONUC: ${results.length - failed.length}/${results.length} basarili`);
  failed.forEach((f) => log('BASARISIZ:', f.name));
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  try { srv.kill('SIGKILL'); } catch (_) { /* */ }
  process.exit(2);
});
