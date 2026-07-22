'use strict'

const dns = require('node:dns')
const http = require('node:http')
const https = require('node:https')
const net = require('node:net')

const DEFAULT_TIMEOUT_MS = 10_000
const DEFAULT_MAX_BYTES = 2 * 1024 * 1024
const DEFAULT_MAX_REDIRECTS = 3

class FeedSecurityError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'FeedSecurityError'
    this.code = code
  }
}

function normalizeHostname(hostname) {
  return String(hostname || '')
    .trim()
    .toLowerCase()
    .replace(/^\[|\]$/g, '')
    .replace(/\.$/, '')
}

function parseIpv4(address) {
  const parts = String(address).split('.')
  if (parts.length !== 4) return null
  const bytes = parts.map(part => Number(part))
  if (bytes.some((byte, index) => !Number.isInteger(byte) || byte < 0 || byte > 255 || String(byte) !== parts[index])) {
    return null
  }
  return bytes
}

function parseIpv6(address) {
  let input = normalizeHostname(address).split('%')[0]
  if (!input || input.includes(':::')) return null

  const ipv4Match = input.match(/(?:^|:)(\d+\.\d+\.\d+\.\d+)$/)
  if (ipv4Match) {
    const ipv4 = parseIpv4(ipv4Match[1])
    if (!ipv4) return null
    const replacement = `${((ipv4[0] << 8) | ipv4[1]).toString(16)}:${((ipv4[2] << 8) | ipv4[3]).toString(16)}`
    input = input.slice(0, input.length - ipv4Match[1].length) + replacement
  }

  const halves = input.split('::')
  if (halves.length > 2) return null
  const left = halves[0] ? halves[0].split(':') : []
  const right = halves.length === 2 && halves[1] ? halves[1].split(':') : []
  const missing = 8 - left.length - right.length
  if ((halves.length === 1 && missing !== 0) || (halves.length === 2 && missing < 1)) return null

  const groups = [...left, ...Array(Math.max(0, missing)).fill('0'), ...right]
  if (groups.length !== 8 || groups.some(group => !/^[0-9a-f]{1,4}$/i.test(group))) return null

  const bytes = []
  for (const group of groups) {
    const value = Number.parseInt(group, 16)
    bytes.push(value >> 8, value & 0xff)
  }
  return bytes
}

function isPrivateIpv4(address) {
  const bytes = parseIpv4(address)
  if (!bytes) return true
  const [a, b, c] = bytes

  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 0 && c === 0) ||
    (a === 192 && b === 0 && c === 2) ||
    (a === 192 && b === 88 && c === 99) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19)) ||
    (a === 198 && b === 51 && c === 100) ||
    (a === 203 && b === 0 && c === 113) ||
    a >= 224
  )
}

function isPrivateIpv6(address) {
  const bytes = parseIpv6(address)
  if (!bytes) return true

  const isIpv4Mapped = bytes.slice(0, 10).every(byte => byte === 0) && bytes[10] === 0xff && bytes[11] === 0xff
  if (isIpv4Mapped) {
    return isPrivateIpv4(bytes.slice(12).join('.'))
  }

  const isUnspecifiedOrCompatible = bytes.slice(0, 12).every(byte => byte === 0)
  if (isUnspecifiedOrCompatible) return true

  // Only globally routable 2000::/3 addresses are accepted. Exclude documentation,
  // benchmarking, ORCHID and 6to4 ranges that should never back an RSS endpoint.
  if ((bytes[0] & 0xe0) !== 0x20) return true
  if (bytes[0] === 0x20 && bytes[1] === 0x01 && bytes[2] === 0x0d && bytes[3] === 0xb8) return true
  if (bytes[0] === 0x20 && bytes[1] === 0x01 && bytes[2] === 0x00 && [0x02, 0x10, 0x20].includes(bytes[3])) return true
  if (bytes[0] === 0x20 && bytes[1] === 0x02) return true

  return false
}

function isPrivateIp(address) {
  const normalized = normalizeHostname(address)
  const family = net.isIP(normalized)
  if (family === 4) return isPrivateIpv4(normalized)
  if (family === 6) return isPrivateIpv6(normalized)
  return true
}

function isPrivateHostname(hostname) {
  const normalized = normalizeHostname(hostname)
  if (!normalized) return true
  if (net.isIP(normalized)) return isPrivateIp(normalized)

  return (
    !normalized.includes('.') ||
    normalized === 'localhost' ||
    normalized === 'metadata.google.internal' ||
    normalized.endsWith('.localhost') ||
    normalized.endsWith('.local') ||
    normalized.endsWith('.internal') ||
    normalized.endsWith('.home') ||
    normalized.endsWith('.lan')
  )
}

function validateFeedUrl(rawUrl) {
  if (typeof rawUrl !== 'string' || rawUrl.length === 0 || rawUrl.length > 2048) {
    throw new FeedSecurityError('INVALID_FEED_URL', 'RSS URL 无效')
  }

  let url
  try {
    url = new URL(rawUrl)
  } catch {
    throw new FeedSecurityError('INVALID_FEED_URL', 'RSS URL 无效')
  }

  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new FeedSecurityError('INVALID_FEED_URL', 'RSS URL 必须使用 HTTP 或 HTTPS')
  }

  if (isPrivateHostname(url.hostname)) {
    throw new FeedSecurityError('UNSAFE_FEED_URL', 'RSS URL 不能指向私有网络')
  }

  url.hash = ''
  return url
}

async function resolveAndValidateHost(hostname, lookup = dns.promises.lookup) {
  const normalized = normalizeHostname(hostname)
  if (net.isIP(normalized)) {
    if (isPrivateIp(normalized)) {
      throw new FeedSecurityError('UNSAFE_FEED_URL', 'RSS URL 不能指向私有网络')
    }
    return [{ address: normalized, family: net.isIP(normalized) }]
  }

  let addresses
  try {
    addresses = await lookup(normalized, { all: true, verbatim: true })
  } catch {
    throw new FeedSecurityError('DNS_LOOKUP_FAILED', 'RSS 域名解析失败')
  }

  if (!Array.isArray(addresses) || addresses.length === 0) {
    throw new FeedSecurityError('DNS_LOOKUP_FAILED', 'RSS 域名解析失败')
  }

  if (addresses.some(result => !result || isPrivateIp(result.address))) {
    throw new FeedSecurityError('UNSAFE_FEED_URL', 'RSS URL 不能指向私有网络')
  }

  return addresses
}

function resolveWithTimeout(hostname, timeoutMs, lookup) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new FeedSecurityError('FETCH_TIMEOUT', 'RSS 请求超时'))
    }, timeoutMs)

    resolveAndValidateHost(hostname, lookup).then(result => {
      clearTimeout(timer)
      resolve(result)
    }, error => {
      clearTimeout(timer)
      reject(error)
    })
  })
}

function requestOnce(url, resolved, timeoutMs, maxBytes) {
  const transport = url.protocol === 'https:' ? https : http
  const originalHostname = normalizeHostname(url.hostname)

  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      callback(value)
    }

    const request = transport.request({
      protocol: url.protocol,
      hostname: resolved.address,
      family: resolved.family,
      port: url.port || undefined,
      path: `${url.pathname}${url.search}`,
      method: 'GET',
      servername: net.isIP(originalHostname) ? undefined : originalHostname,
      headers: {
        Accept: 'application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.2',
        'Accept-Encoding': 'identity',
        Host: url.host,
        'User-Agent': 'IdolRadar-RSS/1.0'
      }
    }, response => {
      const contentEncoding = String(response.headers['content-encoding'] || 'identity').toLowerCase()
      if (!['', 'identity'].includes(contentEncoding)) {
        response.resume()
        finish(reject, new FeedSecurityError('UNSUPPORTED_ENCODING', 'RSS 响应压缩格式不支持'))
        return
      }

      const declaredLength = Number(response.headers['content-length'])
      if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
        response.resume()
        finish(reject, new FeedSecurityError('RESPONSE_TOO_LARGE', 'RSS 响应过大'))
        return
      }

      const chunks = []
      let received = 0
      response.on('data', chunk => {
        received += chunk.length
        if (received > maxBytes) {
          response.destroy(new FeedSecurityError('RESPONSE_TOO_LARGE', 'RSS 响应过大'))
          return
        }
        chunks.push(chunk)
      })
      response.on('end', () => {
        finish(resolve, {
          statusCode: response.statusCode || 0,
          headers: response.headers,
          body: Buffer.concat(chunks).toString('utf8')
        })
      })
      response.on('error', error => finish(reject, error))
    })

    request.setTimeout(timeoutMs, () => {
      request.destroy(new FeedSecurityError('FETCH_TIMEOUT', 'RSS 请求超时'))
    })
    request.on('error', error => finish(reject, error))
    request.end()
  })
}

async function fetchText(rawUrl, options = {}) {
  const timeoutMs = Number.isInteger(options.timeoutMs) ? options.timeoutMs : DEFAULT_TIMEOUT_MS
  const maxBytes = Number.isInteger(options.maxBytes) ? options.maxBytes : DEFAULT_MAX_BYTES
  const maxRedirects = Number.isInteger(options.maxRedirects) ? options.maxRedirects : DEFAULT_MAX_REDIRECTS
  let current = validateFeedUrl(rawUrl)

  for (let redirectCount = 0; redirectCount <= maxRedirects; redirectCount += 1) {
    const addresses = await resolveWithTimeout(current.hostname, timeoutMs, options.lookup)
    const response = await requestOnce(current, addresses[0], timeoutMs, maxBytes)

    if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location) {
      if (redirectCount === maxRedirects) {
        throw new FeedSecurityError('TOO_MANY_REDIRECTS', 'RSS 重定向过多')
      }
      current = validateFeedUrl(new URL(response.headers.location, current).toString())
      continue
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw new FeedSecurityError('HTTP_ERROR', `RSS 请求失败（HTTP ${response.statusCode}）`)
    }

    return response.body
  }

  throw new FeedSecurityError('TOO_MANY_REDIRECTS', 'RSS 重定向过多')
}

module.exports = {
  FeedSecurityError,
  fetchText,
  isPrivateHostname,
  isPrivateIp,
  resolveAndValidateHost,
  resolveWithTimeout,
  validateFeedUrl
}
