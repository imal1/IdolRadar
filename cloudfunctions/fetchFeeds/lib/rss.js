'use strict'

const crypto = require('node:crypto')
const { XMLParser, XMLValidator } = require('fast-xml-parser')

const MAX_XML_BYTES = 2 * 1024 * 1024
const MAX_LINK_LENGTH = 2048
const MAX_TITLE_LENGTH = 120
const MAX_SUMMARY_LENGTH = 500

class FeedParseError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'FeedParseError'
    this.code = code
  }
}

function decodeEntities(value) {
  const named = {
    amp: '&',
    apos: "'",
    gt: '>',
    lt: '<',
    nbsp: ' ',
    quot: '"'
  }

  return value.replace(/&(#(?:x[0-9a-f]+|\d+)|[a-z]+);/gi, (match, entity) => {
    if (entity[0] === '#') {
      const isHex = entity[1].toLowerCase() === 'x'
      const codePoint = Number.parseInt(entity.slice(isHex ? 2 : 1), isHex ? 16 : 10)
      if (Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff) {
        try {
          return String.fromCodePoint(codePoint)
        } catch {
          return match
        }
      }
      return match
    }
    return Object.prototype.hasOwnProperty.call(named, entity.toLowerCase())
      ? named[entity.toLowerCase()]
      : match
  })
}

function stripHtml(value) {
  if (value === undefined || value === null) return ''
  return decodeEntities(String(value)
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' '))
    .replace(/\s+/g, ' ')
    .trim()
}

function truncate(value, maxLength) {
  return [...value].slice(0, maxLength).join('')
}

function textValue(value) {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return value.map(textValue).find(Boolean) || ''
  if (typeof value === 'object') {
    return textValue(value['#text'] ?? value.text ?? value.value ?? '')
  }
  return ''
}

function canonicalizeLink(rawLink) {
  const value = textValue(rawLink).trim()
  if (!value || value.length > MAX_LINK_LENGTH) {
    throw new FeedParseError('INVALID_LINK', '动态链接无效')
  }

  let url
  try {
    url = new URL(value)
  } catch {
    throw new FeedParseError('INVALID_LINK', '动态链接无效')
  }

  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new FeedParseError('INVALID_LINK', '动态链接无效')
  }

  url.hash = ''
  return url.toString()
}

function normalizeDate(value, fallback) {
  const parsed = value instanceof Date ? new Date(value.getTime()) : new Date(textValue(value))
  if (Number.isFinite(parsed.getTime())) return parsed

  const fallbackDate = fallback instanceof Date ? new Date(fallback.getTime()) : new Date(fallback)
  if (!Number.isFinite(fallbackDate.getTime())) {
    throw new FeedParseError('INVALID_FETCH_TIME', '抓取时间无效')
  }
  return fallbackDate
}

function normalizeEntry(entry, defaults = {}) {
  const fetchedAt = defaults.fetchedAt || new Date()
  const rawTitle = stripHtml(textValue(entry && entry.title))
  const rawSummary = stripHtml(textValue(entry && entry.summary))
  const title = truncate(rawTitle || rawSummary || '新动态', MAX_TITLE_LENGTH)
  const summary = truncate(rawSummary || title, MAX_SUMMARY_LENGTH)

  return {
    title,
    summary,
    link: canonicalizeLink(entry && entry.link),
    publishedAt: normalizeDate(entry && entry.publishedAt, fetchedAt)
  }
}

function asArray(value) {
  if (value === undefined || value === null) return []
  return Array.isArray(value) ? value : [value]
}

function atomLink(value) {
  const links = asArray(value)
  const preferred = links.find(link => {
    if (!link || typeof link !== 'object') return false
    const rel = textValue(link['@_rel']).toLowerCase()
    return link['@_href'] && (!rel || rel === 'alternate')
  })
  const anyHref = links.find(link => link && typeof link === 'object' && link['@_href'])
  return preferred ? preferred['@_href'] : anyHref ? anyHref['@_href'] : textValue(links[0])
}

function rssLink(item) {
  const direct = textValue(item && item.link)
  if (direct) return direct
  const guid = textValue(item && item.guid)
  return /^https?:\/\//i.test(guid) ? guid : ''
}

function parseFeed(xml, fetchedAt = new Date()) {
  if (typeof xml !== 'string' || xml.trim().length === 0 || Buffer.byteLength(xml, 'utf8') > MAX_XML_BYTES) {
    throw new FeedParseError('INVALID_XML', 'RSS XML 无效')
  }
  if (/<!DOCTYPE|<!ENTITY/i.test(xml)) {
    throw new FeedParseError('UNSAFE_XML', 'RSS XML 包含不安全声明')
  }

  const validation = XMLValidator.validate(xml)
  if (validation !== true) {
    throw new FeedParseError('INVALID_XML', 'RSS XML 解析失败')
  }

  let document
  try {
    document = new XMLParser({
      ignoreAttributes: false,
      attributeNamePrefix: '@_',
      parseAttributeValue: false,
      parseTagValue: false,
      processEntities: true,
      removeNSPrefix: true,
      textNodeName: '#text',
      trimValues: true
    }).parse(xml)
  } catch {
    throw new FeedParseError('INVALID_XML', 'RSS XML 解析失败')
  }

  const fallback = fetchedAt && fetchedAt.fetchedAt ? fetchedAt.fetchedAt : fetchedAt
  let rawEntries

  if (document && document.rss && document.rss.channel) {
    rawEntries = asArray(document.rss.channel.item).map(item => ({
      title: item && item.title,
      summary: item && (item.description ?? item.encoded ?? item.content),
      link: rssLink(item),
      publishedAt: item && (item.pubDate ?? item.date ?? item.updated)
    }))
  } else if (document && document.feed) {
    rawEntries = asArray(document.feed.entry).map(entry => ({
      title: entry && entry.title,
      summary: entry && (entry.summary ?? entry.content),
      link: atomLink(entry && entry.link),
      publishedAt: entry && (entry.published ?? entry.updated)
    }))
  } else {
    throw new FeedParseError('UNSUPPORTED_FEED', '仅支持 RSS 2.0 或 Atom')
  }

  if (rawEntries.length === 0) {
    throw new FeedParseError('EMPTY_FEED', 'RSS 未包含任何动态')
  }

  const entries = []
  const seenLinks = new Set()
  for (const rawEntry of rawEntries) {
    try {
      const entry = normalizeEntry(rawEntry, { fetchedAt: fallback })
      if (seenLinks.has(entry.link)) continue
      seenLinks.add(entry.link)
      entries.push(entry)
    } catch (error) {
      if (!(error instanceof FeedParseError) || error.code !== 'INVALID_LINK') throw error
    }
  }
  if (entries.length === 0) {
    throw new FeedParseError('EMPTY_FEED', 'RSS 未包含可用动态')
  }
  return entries
}

function postIdForLink(link) {
  return crypto.createHash('sha256').update(canonicalizeLink(link), 'utf8').digest('hex')
}

module.exports = {
  FeedParseError,
  canonicalizeLink,
  normalizeEntry,
  parseFeed,
  postIdForLink,
  stripHtml
}
