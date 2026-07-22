'use strict'

const cloud = require('wx-server-sdk')
const { fetchText } = require('./lib/security')
const { parseFeed, postIdForLink } = require('./lib/rss')
const { selectLatestPostsByIdol } = require('./lib/posts')

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()
const DATABASE_PAGE_SIZE = 100
const DEFAULT_SOURCE_CONCURRENCY = 4
const DEFAULT_MAX_ENTRIES_PER_SOURCE = 100
const KNOWN_ERROR_CODES = new Set([
  'DNS_LOOKUP_FAILED',
  'EMPTY_FEED',
  'FETCH_TIMEOUT',
  'HTTP_ERROR',
  'INVALID_FEED_URL',
  'INVALID_LINK',
  'INVALID_SOURCE',
  'INVALID_XML',
  'RESPONSE_TOO_LARGE',
  'TOO_MANY_REDIRECTS',
  'UNSAFE_FEED_URL',
  'UNSAFE_XML',
  'UNSUPPORTED_ENCODING',
  'UNSUPPORTED_FEED'
])

function responseOk(data) {
  return { ok: true, data }
}

function responseError(code, message) {
  return { ok: false, error: { code, message } }
}

function isMiniProgramCall() {
  try {
    const context = cloud.getWXContext()
    return Boolean(context && context.OPENID)
  } catch {
    return false
  }
}

function positiveIntegerFromEnv(name, fallback, maximum) {
  const value = Number.parseInt(process.env[name] || '', 10)
  return Number.isInteger(value) && value > 0 && value <= maximum ? value : fallback
}

function publicErrorCode(error) {
  const code = error && error.code
  return typeof code === 'string' && KNOWN_ERROR_CODES.has(code) ? code : 'INTERNAL_ERROR'
}

function isDuplicateError(error) {
  const code = error && (error.errCode || error.code)
  const message = String((error && error.message) || '')
  return (
    code === -502001 ||
    (code === 'DATABASE_REQUEST_FAILED' && /duplicate/i.test(message)) ||
    /duplicate|already exists/i.test(message)
  )
}

function validateSource(source) {
  if (
    !source ||
    typeof source._id !== 'string' ||
    !source._id ||
    typeof source.idolId !== 'string' ||
    !source.idolId ||
    typeof source.rssUrl !== 'string' ||
    !source.rssUrl
  ) {
    const error = new Error('Invalid source')
    error.code = 'INVALID_SOURCE'
    throw error
  }
}

async function loadEnabledSources() {
  const sources = []
  let offset = 0

  while (true) {
    const result = await db.collection('sources')
      .where({ enabled: true })
      .orderBy('_id', 'asc')
      .skip(offset)
      .limit(DATABASE_PAGE_SIZE)
      .get()
    sources.push(...result.data)

    if (result.data.length < DATABASE_PAGE_SIZE) break
    offset += result.data.length
  }

  return sources
}

async function updateSourceStatus(sourceId, data) {
  try {
    await db.collection('sources').doc(sourceId).update({ data })
  } catch {
    console.error('[fetchFeeds]', { sourceId, code: 'SOURCE_STATUS_UPDATE_FAILED' })
  }
}

async function insertPost(source, entry, fetchedAt) {
  const id = postIdForLink(entry.link)
  const post = {
    _id: id,
    idolId: source.idolId,
    sourceId: source._id,
    channel: typeof source.channel === 'string' && source.channel.trim()
      ? source.channel.trim().slice(0, 32)
      : 'RSS',
    title: entry.title,
    summary: entry.summary,
    link: entry.link,
    publishedAt: entry.publishedAt,
    fetchedAt
  }

  try {
    await db.collection('posts').add({ data: post })
    return post
  } catch (error) {
    if (isDuplicateError(error)) return null
    throw error
  }
}

async function processSource(source, options) {
  const fetchedAt = new Date()
  const newPosts = []

  try {
    validateSource(source)
    const xml = await fetchText(source.rssUrl, {
      timeoutMs: options.timeoutMs,
      maxBytes: options.maxResponseBytes,
      maxRedirects: options.maxRedirects
    })
    const entries = parseFeed(xml, fetchedAt)
      .sort((left, right) => right.publishedAt.getTime() - left.publishedAt.getTime())
      .slice(0, options.maxEntriesPerSource)
    for (const entry of entries) {
      const post = await insertPost(source, entry, fetchedAt)
      if (post) newPosts.push(post)
    }

    await updateSourceStatus(source._id, {
      lastFetchAt: db.serverDate(),
      lastFetchStatus: 'success',
      lastFetchErrorCode: null,
      lastFetchItemCount: entries.length,
      lastFetchNewCount: newPosts.length
    })

    return { ok: true, newPosts }
  } catch (error) {
    const code = publicErrorCode(error)
    if (source && typeof source._id === 'string' && source._id) {
      await updateSourceStatus(source._id, {
        lastFetchAt: db.serverDate(),
        lastFetchStatus: 'failed',
        lastFetchErrorCode: code,
        lastFetchItemCount: 0,
        lastFetchNewCount: newPosts.length
      })
    }
    console.error('[fetchFeeds]', {
      sourceId: source && typeof source._id === 'string' ? source._id : 'unknown',
      code
    })
    return { ok: false, newPosts }
  }
}

async function mapWithConcurrency(items, concurrency, worker) {
  const results = new Array(items.length)
  let nextIndex = 0

  async function run() {
    while (true) {
      const index = nextIndex
      nextIndex += 1
      if (index >= items.length) return
      results[index] = await worker(items[index], index)
    }
  }

  const workerCount = Math.min(concurrency, items.length)
  await Promise.all(Array.from({ length: workerCount }, run))
  return results
}

async function notifyLatestPosts(newPosts) {
  const latestByIdol = selectLatestPostsByIdol(newPosts)
  let succeeded = 0
  let failed = 0

  for (const post of latestByIdol.values()) {
    try {
      const call = await cloud.callFunction({
        name: 'sendNotify',
        data: { postId: post._id, idolId: post.idolId }
      })
      if (!call || !call.result || call.result.ok !== true) {
        failed += 1
        console.error('[fetchFeeds]', { idolId: post.idolId, code: 'NOTIFY_FAILED' })
        continue
      }
      succeeded += 1
    } catch {
      failed += 1
      console.error('[fetchFeeds]', { idolId: post.idolId, code: 'NOTIFY_FAILED' })
    }
  }

  return { requested: latestByIdol.size, succeeded, failed }
}

exports.main = async () => {
  if (isMiniProgramCall()) {
    return responseError('FORBIDDEN', '禁止从小程序端直接调用')
  }

  try {
    const options = {
      timeoutMs: positiveIntegerFromEnv('RSS_TIMEOUT_MS', 10_000, 30_000),
      maxResponseBytes: positiveIntegerFromEnv('RSS_MAX_RESPONSE_BYTES', 2 * 1024 * 1024, 5 * 1024 * 1024),
      maxRedirects: positiveIntegerFromEnv('RSS_MAX_REDIRECTS', 3, 5),
      maxEntriesPerSource: positiveIntegerFromEnv('RSS_MAX_ENTRIES_PER_SOURCE', DEFAULT_MAX_ENTRIES_PER_SOURCE, 200)
    }
    const concurrency = positiveIntegerFromEnv('RSS_SOURCE_CONCURRENCY', DEFAULT_SOURCE_CONCURRENCY, 10)
    const sources = await loadEnabledSources()
    const results = await mapWithConcurrency(
      sources,
      concurrency,
      source => processSource(source, options)
    )
    const newPosts = results.flatMap(result => result.newPosts)
    const notifications = await notifyLatestPosts(newPosts)

    return responseOk({
      sources: {
        total: sources.length,
        succeeded: results.filter(result => result.ok).length,
        failed: results.filter(result => !result.ok).length
      },
      postsInserted: newPosts.length,
      notifications
    })
  } catch {
    console.error('[fetchFeeds]', { code: 'INTERNAL_ERROR' })
    return responseError('INTERNAL_ERROR', '抓取任务执行失败')
  }
}

exports._helpers = {
  isDuplicateError,
  mapWithConcurrency,
  publicErrorCode,
  validateSource
}
