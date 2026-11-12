'use strict';

// Native JSON decoding remains the owner of syntax/string decoding. The raw
// member scan adds the duplicate-name guarantee JSON.parse cannot provide.
const jsonMedia = /^[\t ]*application\/json[\t ]*(?:;[\t ]*charset[\t ]*=[\t ]*(?:utf-8|"utf-8")[\t ]*)?$/i;
const objectFields = {
  site: new Set(['id', 'domain']), app: new Set(['id', 'bundle']),
  device: new Set(['ip', 'os', 'ua', 'lmt']), user: new Set(['id']),
};
const rootFields = new Set(['id', ...Object.keys(objectFields)]);

function badRequest() {
  const error = new Error('Invalid receiver request');
  error.statusCode = 400;
  return error;
}

function validHeaders(rawHeaders) {
  const values = (name) => {
    const result = [];
    for (let i = 0; i < rawHeaders.length; i += 2) {
      if (rawHeaders[i].toLowerCase() === name) result.push(rawHeaders[i + 1]);
    }
    return result;
  };
  const types = values('content-type'), encodings = values('content-encoding');
  return types.length === 1 && jsonMedia.test(types[0]) && encodings.length <= 1
    && (encodings.length === 0 || /^[\t ]*identity[\t ]*$/i.test(encodings[0]));
}

// Compare the original decimal lexeme, without binary64 rounding or converting
// an arbitrarily long exponent to a machine integer. JSON syntax is checked by
// the native parser before its numeric reviver invokes this function.
function exactLmt(token) {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
  if (!match) throw badRequest();
  const fraction = match[3] || '';
  const digits = (match[2] + fraction).replace(/^0+/, '');
  if (!digits) return 0;
  const significant = digits.replace(/0+$/, '');
  if (match[1] || significant !== '1') throw badRequest();
  const expected = String(fraction.length - (digits.length - significant.length));
  const exponent = match[4] || '0';
  const negative = exponent[0] === '-';
  const magnitude = exponent.replace(/^[+-]/, '').replace(/^0+/, '') || '0';
  const normalized = negative && magnitude !== '0' ? '-' + magnitude : magnitude;
  if (normalized !== expected) throw badRequest();
  return 1;
}

function rejectDuplicateMembers(text) {
  let cursor = 0;
  const whitespace = () => { while (cursor < text.length && /[\t\n\r ]/.test(text[cursor])) cursor++; };
  const string = () => {
    const start = cursor++;
    while (cursor < text.length) {
      if (text[cursor] === '\\') { cursor += 2; continue; }
      if (text[cursor++] === '"') return JSON.parse(text.slice(start, cursor));
    }
    throw badRequest();
  };
  const value = () => {
    whitespace();
    if (text[cursor] === '{') {
      cursor++; whitespace();
      const names = new Set();
      if (text[cursor] === '}') { cursor++; return; }
      for (;;) {
        whitespace();
        const name = string();
        if (names.has(name)) throw badRequest();
        names.add(name); whitespace(); cursor++; // Colon: native syntax already checked.
        value(); whitespace();
        if (text[cursor++] === '}') return;
      }
    }
    if (text[cursor] === '[') {
      cursor++; whitespace();
      if (text[cursor] === ']') { cursor++; return; }
      for (;;) {
        value(); whitespace();
        if (text[cursor++] === ']') return;
      }
    }
    if (text[cursor] === '"') { string(); return; }
    while (cursor < text.length && !/[\t\n\r ,}\]]/.test(text[cursor])) cursor++;
  };
  value();
}

function validString(value, max) {
  return typeof value === 'string' && value.isWellFormed() && [...value].length <= max;
}

function validateInput(input) {
  const object = (value) => value != null && typeof value === 'object' && !Array.isArray(value);
  if (!object(input) || !validString(input.id, 256) || /^[\t-\r ]*$/.test(input.id)
      || !object(input.device) || (input.site == null && input.app == null)) throw badRequest();
  if (Object.keys(input).some((key) => !key.isWellFormed() || !rootFields.has(key))) throw badRequest();
  for (const [name, allowed] of Object.entries(objectFields)) {
    const entry = input[name];
    if (entry == null && name !== 'device') continue;
    if (!object(entry)) throw badRequest();
    for (const [key, value] of Object.entries(entry)) {
      if (!key.isWellFormed() || !allowed.has(key)) throw badRequest();
      if (name === 'device' && key === 'lmt') {
        if (typeof value !== 'number' || (value !== 0 && value !== 1)) throw badRequest();
      } else if (value != null && !validString(value, 4096)) throw badRequest();
    }
  }
  if (!Object.hasOwn(input.device, 'lmt')) input.device.lmt = 0;
  return input;
}

function parseReceiverBody(bytes) {
  try {
    if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) throw badRequest();
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
    const input = JSON.parse(text, (key, value, context) => {
      if (key === 'lmt' && typeof value === 'number') return exactLmt(context.source);
      return value;
    });
    rejectDuplicateMembers(text);
    return validateInput(input);
  } catch { throw badRequest(); }
}

module.exports = { validHeaders, parseReceiverBody, exactLmt };
