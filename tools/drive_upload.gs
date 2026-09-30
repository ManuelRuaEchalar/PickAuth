/**
 * PickupAuth · receptor de subidas (Google Apps Script, publicado como aplicación web).
 *
 * Guarda cada zip en  <carpeta raíz>/<participante>/<archivo>.zip  y lleva una hoja de control
 * "PickupAuth · control" (solo estadísticas, no datos) en la misma carpeta:
 *   - "participantes": una fila por persona, con su última subida (roja si pasan más de 36 h).
 *   - "subidas": una fila por zip recibido.
 *
 * Configuración (una vez): Configuración del proyecto → Propiedades del script:
 *   TOKEN      = la clave que tiene la app (pickupauth.uploadToken en local.properties)
 *   FOLDER_ID  = el ID de la carpeta de Drive
 * Luego: ejecutar setup() una vez (pide permisos) y publicar como aplicación web
 * (Ejecutar como: yo · Quién tiene acceso: cualquier usuario).
 * Para cambiar el código: Implementar → Gestionar implementaciones → editar → Nueva versión
 * (así la URL no cambia).
 */

const PARTICIPANT_HEADERS = ['participante', 'dispositivo', 'versión app', 'última subida', 'horas sin subir',
  'subidas', 'desbloqueos', 'con toma', 'en su sitio', 'falsos disparos', 'etiquetados',
  'horas de captura caída', 'batería % (última)', 'pendientes en el teléfono', 'captura activa'];
const UPLOAD_HEADERS = ['recibido', 'participante', 'archivo', 'KB', 'desde', 'desbloqueos nuevos', 'con toma',
  'en su sitio', 'falsos disparos', 'etiquetados', 'etiquetas tardías', 'horas caída', 'batería %',
  'modo', 'versión app', 'pendientes', 'episodios en el teléfono', 'captura activa'];

function doPost(e) {
  const props = PropertiesService.getScriptProperties();
  let body;
  try {
    body = JSON.parse(e.postData.contents);
  } catch (err) {
    return json({ ok: false, error: 'json' });
  }
  if (!body.token || body.token !== props.getProperty('TOKEN')) return json({ ok: false, error: 'token' });

  const subject = String(body.subject || 'SIN_ID').replace(/[^\w.-]/g, '_');
  const name = String(body.name || '').replace(/[^\w.-]/g, '_');
  if (!name.endsWith('.zip')) return json({ ok: false, error: 'name' });

  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const root = DriveApp.getFolderById(props.getProperty('FOLDER_ID'));
    const folder = childFolder(root, subject);
    // Idempotente: si un reintento trae un zip que ya llegó, se confirma sin duplicarlo.
    if (folder.getFilesByName(name).hasNext()) return json({ ok: true, duplicate: true });

    const bytes = Utilities.base64Decode(body.zip_b64);
    folder.createFile(Utilities.newBlob(bytes, 'application/zip', name));
    logUpload(root, subject, name, Math.round(bytes.length / 1024), body.manifest || {});
    return json({ ok: true });
  } finally {
    lock.releaseLock();
  }
}

/** Para probar la URL desde el navegador. */
function doGet() {
  return json({ ok: true, service: 'pickupauth' });
}

/** Ejecutar una vez desde el editor: pide permisos y crea la hoja de control. */
function setup() {
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('TOKEN') || !props.getProperty('FOLDER_ID')) {
    throw new Error('Faltan las propiedades TOKEN y/o FOLDER_ID');
  }
  const root = DriveApp.getFolderById(props.getProperty('FOLDER_ID'));
  const ss = controlSheet(root);
  Logger.log('Listo. Hoja de control: ' + ss.getUrl());
}

// ---------------------------------------------------------------- hoja de control

function controlSheet(root) {
  const props = PropertiesService.getScriptProperties();
  const id = props.getProperty('SHEET_ID');
  if (id) return SpreadsheetApp.openById(id);

  const ss = SpreadsheetApp.create('PickupAuth · control');
  DriveApp.getFileById(ss.getId()).moveTo(root);
  const p = ss.getSheets()[0].setName('participantes');
  p.appendRow(PARTICIPANT_HEADERS);
  p.setFrozenRows(1);
  p.getRange(1, 1, 1, PARTICIPANT_HEADERS.length).setFontWeight('bold');
  // "horas sin subir" se calcula en la hoja; en rojo si pasa de 36 h.
  const hours = p.getRange('E2:E1000');
  p.setConditionalFormatRules([SpreadsheetApp.newConditionalFormatRule()
    .whenNumberGreaterThan(36).setBackground('#f4c7c3').setRanges([hours]).build()]);
  const u = ss.insertSheet('subidas');
  u.appendRow(UPLOAD_HEADERS);
  u.setFrozenRows(1);
  u.getRange(1, 1, 1, UPLOAD_HEADERS.length).setFontWeight('bold');
  ss.setRecalculationInterval(SpreadsheetApp.RecalculationInterval.HOUR);
  props.setProperty('SHEET_ID', ss.getId());
  return ss;
}

function logUpload(root, subject, name, kb, m) {
  const ss = controlSheet(root);
  const now = new Date();
  const withPickup = m.new_with_pickup || 0;
  const unlocks = (m.new_unlock || 0) + (m.new_in_place || 0);
  const down = round2(m.downtime_h || 0);
  const nOnDevice = (m.episodes_on_device || []).length;

  ss.getSheetByName('subidas').appendRow([now, subject, name, kb,
    m.period_from_ms ? new Date(m.period_from_ms) : '', unlocks, withPickup, m.new_in_place || 0,
    m.new_false_trigger || 0, m.new_labeled || 0, m.late_labels || 0, down, m.battery_pct,
    m.capture_mode, m.app_version, m.pending_zips, nOnDevice, m.capture_enabled]);

  const p = ss.getSheetByName('participantes');
  const ids = p.getLastRow() > 1 ? p.getRange(2, 1, p.getLastRow() - 1, 1).getValues().map(r => String(r[0])) : [];
  let row = ids.indexOf(subject) + 2;
  let prev = null;
  if (row < 2) {
    row = p.getLastRow() + 1;
  } else {
    prev = p.getRange(row, 1, 1, PARTICIPANT_HEADERS.length).getValues()[0];
  }
  const acc = (i, add) => (prev ? Number(prev[i]) || 0 : 0) + add;
  p.getRange(row, 1, 1, PARTICIPANT_HEADERS.length).setValues([[
    subject, m.device || '', m.app_version || '', now, '',
    acc(5, 1), acc(6, unlocks), acc(7, withPickup), acc(8, m.new_in_place || 0),
    acc(9, m.new_false_trigger || 0), acc(10, m.new_labeled || 0), round2(acc(11, down)),
    m.battery_pct, m.pending_zips, m.capture_enabled,
  ]]);
  p.getRange(row, 5).setFormula('=ROUND((NOW()-D' + row + ')*24,1)');
}

// ---------------------------------------------------------------- utilidades

function childFolder(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function round2(x) {
  return Math.round(x * 100) / 100;
}

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
