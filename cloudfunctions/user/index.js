'use strict'

const cloud = require('wx-server-sdk')
const { AppError, assert, ok, fail } = require('./lib/errors')
const { decodeCursor, encodeCursor, normalizeDate } = require('./lib/pagination')
const { startOfShanghaiDay } = require('./lib/time')

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()
const command = db.command
const PAGE_SIZE = 20
const DATABASE_PAGE_SIZE = 100
const MAX_DOCUMENT_ID_LENGTH = 128
const MAX_SUBSCRIBE_QUOTA = 100

function validateDocumentId(value, fieldName) {
  assert(
    typeof value === 'string' &&
      value.length > 0 &&
      value.length <= MAX_DOCUMENT_ID_LENGTH &&
      !/[\u0000-\u001f\u007f]/.test(value),
    'INVALID_INPUT',
    `${fieldName}无效`
  )
  return value
}

function getOpenId() {
  const context = cloud.getWXContext()
  const openId = context && context.OPENID

  assert(
    typeof openId === 'string' && openId.length > 0 && openId.length <= MAX_DOCUMENT_ID_LENGTH,
    'UNAUTHORIZED',
    '无法获取微信身份'
  )

  return openId
}

async function findOne(collectionName, condition) {
  const result = await db.collection(collectionName).where(condition).limit(1).get()
  return result.data[0] || null
}

function isDuplicateError(error) {
  const code = error && (error.errCode || error.code)
  const message = String((error && error.message) || '')
  return code === -502001 || code === 'DATABASE_REQUEST_FAILED' && /duplicate/i.test(message) || /duplicate|already exists/i.test(message)
}

async function ensureUser(openId) {
  let user = await findOne('users', { _id: openId })

  if (!user) {
    try {
      await db.collection('users').add({
        data: {
          _id: openId,
          openid: openId,
          idolId: null,
          guardingSince: null,
          subscribeQuota: 0,
          subscribedAt: null,
          phone: null,
          billing: null,
          createdAt: db.serverDate(),
          updatedAt: db.serverDate()
        }
      })
    } catch (error) {
      if (!isDuplicateError(error)) {
        throw error
      }
    }
    user = await findOne('users', { _id: openId })
  }

  if (!user) {
    throw new Error('User record was not created')
  }

  const repair = {}
  if (user.openid !== openId) repair.openid = openId
  if (!Number.isSafeInteger(user.subscribeQuota) || user.subscribeQuota < 0) repair.subscribeQuota = 0
  else if (user.subscribeQuota > MAX_SUBSCRIBE_QUOTA) repair.subscribeQuota = MAX_SUBSCRIBE_QUOTA
  if (!Object.prototype.hasOwnProperty.call(user, 'idolId')) repair.idolId = null
  if (!Object.prototype.hasOwnProperty.call(user, 'guardingSince')) repair.guardingSince = null

  if (Object.keys(repair).length > 0) {
    repair.updatedAt = db.serverDate()
    await db.collection('users').doc(openId).update({ data: repair })
    user = await findOne('users', { _id: openId })
  }

  return user
}

function serializeUser(user) {
  return {
    idolId: typeof user.idolId === 'string' ? user.idolId : null,
    guardingSince: normalizeDate(user.guardingSince),
    subscribeQuota: Number.isSafeInteger(user.subscribeQuota) && user.subscribeQuota > 0
      ? Math.min(user.subscribeQuota, MAX_SUBSCRIBE_QUOTA)
      : 0,
    subscribedAt: normalizeDate(user.subscribedAt),
    createdAt: normalizeDate(user.createdAt),
    updatedAt: normalizeDate(user.updatedAt)
  }
}

function serializeIdol(idol, sourceCount = 0) {
  return {
    _id: idol._id,
    name: typeof idol.name === 'string' ? idol.name : '',
    avatar: typeof idol.avatar === 'string' ? idol.avatar : '',
    bio: typeof idol.bio === 'string' ? idol.bio : '',
    enabled: idol.enabled === true,
    sourceCount
  }
}

function serializePost(post, sourceMap) {
  const source = sourceMap.get(post.sourceId)
  return {
    _id: post._id,
    idolId: post.idolId,
    sourceId: post.sourceId,
    channel: typeof post.channel === 'string' && post.channel
      ? post.channel
      : source && typeof source.channel === 'string' && source.channel
        ? source.channel
        : 'RSS',
    title: typeof post.title === 'string' ? post.title : '',
    summary: typeof post.summary === 'string' ? post.summary : '',
    link: typeof post.link === 'string' ? post.link : '',
    publishedAt: normalizeDate(post.publishedAt),
    fetchedAt: normalizeDate(post.fetchedAt)
  }
}

async function loadAll(queryFactory) {
  const documents = []
  let offset = 0

  while (true) {
    const result = await queryFactory()
      .orderBy('_id', 'asc')
      .skip(offset)
      .limit(DATABASE_PAGE_SIZE)
      .get()
    documents.push(...result.data)

    if (result.data.length < DATABASE_PAGE_SIZE) {
      break
    }
    offset += result.data.length
  }

  return documents
}

async function loadSources(idolId, enabledOnly = false) {
  const condition = enabledOnly ? { idolId, enabled: true } : { idolId }
  return loadAll(() => db.collection('sources').where(condition))
}

async function loadIdol(idolId) {
  if (!idolId) return null
  return findOne('idols', { _id: idolId })
}

async function queryFeedPage(idolId, cursor, sources) {
  const sourceMap = new Map(sources.map(source => [source._id, source]))
  let condition = { idolId }

  if (cursor) {
    condition = command.or([
      { idolId, publishedAt: command.lt(cursor.publishedAt) },
      {
        idolId,
        publishedAt: command.eq(cursor.publishedAt),
        _id: command.lt(cursor.id)
      }
    ])
  }

  const result = await db.collection('posts')
    .where(condition)
    .orderBy('publishedAt', 'desc')
    .orderBy('_id', 'desc')
    .limit(PAGE_SIZE + 1)
    .get()

  const hasMore = result.data.length > PAGE_SIZE
  const page = result.data.slice(0, PAGE_SIZE)
  const posts = page.map(post => serializePost(post, sourceMap))

  return {
    posts,
    hasMore,
    nextCursor: hasMore && page.length > 0 ? encodeCursor(page[page.length - 1]) : null
  }
}

async function bootstrap(openId) {
  const user = await ensureUser(openId)
  const serialized = serializeUser(user)
  return { user: serialized, hasIdol: Boolean(serialized.idolId) }
}

async function getHome(openId) {
  const user = await ensureUser(openId)
  const serializedUser = serializeUser(user)

  if (!serializedUser.idolId) {
    return {
      user: serializedUser,
      idol: null,
      stats: { todayPosts: 0, sourceCount: 0, signalStrength: '无信号', latestUpdateAt: null },
      posts: [],
      hasMore: false,
      nextCursor: null
    }
  }

  const idolId = serializedUser.idolId
  const [idol, sources] = await Promise.all([
    loadIdol(idolId),
    loadSources(idolId, false)
  ])

  if (!idol) {
    return {
      user: serializedUser,
      idol: null,
      stats: { todayPosts: 0, sourceCount: 0, signalStrength: '无信号', latestUpdateAt: null },
      posts: [],
      hasMore: false,
      nextCursor: null
    }
  }

  const enabledSources = sources.filter(source => source.enabled === true)
  const [feed, todayResult] = await Promise.all([
    queryFeedPage(idolId, null, sources),
    db.collection('posts').where({
      idolId,
      publishedAt: command.gte(startOfShanghaiDay())
    }).count()
  ])

  return {
    user: serializedUser,
    idol: serializeIdol(idol, enabledSources.length),
    stats: {
      todayPosts: todayResult.total,
      sourceCount: enabledSources.length,
      signalStrength: enabledSources.length > 0 ? '满格' : '无信号',
      latestUpdateAt: feed.posts[0] ? feed.posts[0].publishedAt : null
    },
    ...feed
  }
}

async function listIdols(openId) {
  const [user, idols, sources] = await Promise.all([
    ensureUser(openId),
    loadAll(() => db.collection('idols').where({ enabled: true })),
    loadAll(() => db.collection('sources').where({ enabled: true }))
  ])

  const sourceCounts = new Map()
  for (const source of sources) {
    if (typeof source.idolId !== 'string') continue
    sourceCounts.set(source.idolId, (sourceCounts.get(source.idolId) || 0) + 1)
  }

  return {
    idols: idols.map(idol => serializeIdol(idol, sourceCounts.get(idol._id) || 0)),
    currentIdolId: typeof user.idolId === 'string' ? user.idolId : null
  }
}

async function setIdol(openId, event) {
  const idolId = validateDocumentId(event && event.idolId, 'idolId')
  const [user, idol] = await Promise.all([
    ensureUser(openId),
    findOne('idols', { _id: idolId, enabled: true })
  ])

  if (!idol) {
    throw new AppError('IDOL_NOT_FOUND', '守护对象不存在或已停用')
  }

  if (user.idolId !== idolId) {
    await db.collection('users').doc(openId).update({
      data: {
        idolId,
        guardingSince: db.serverDate(),
        updatedAt: db.serverDate()
      }
    })
  }

  const [updatedUser, enabledSources] = await Promise.all([
    findOne('users', { _id: openId }),
    loadSources(idolId, true)
  ])

  return {
    user: serializeUser(updatedUser),
    idol: serializeIdol(idol, enabledSources.length)
  }
}

async function recordSubscription(openId, event) {
  assert(event && event.accepted === true, 'INVALID_INPUT', '订阅结果无效')
  await ensureUser(openId)

  const result = await db.collection('users').where({
    _id: openId,
    subscribeQuota: command.lt(MAX_SUBSCRIBE_QUOTA)
  }).update({
    data: {
      subscribeQuota: command.inc(1),
      subscribedAt: db.serverDate(),
      updatedAt: db.serverDate()
    }
  })
  const updated = result && result.stats && Number.isInteger(result.stats.updated)
    ? result.stats.updated
    : result && Number.isInteger(result.updated)
      ? result.updated
      : 0
  if (updated !== 1) {
    throw new AppError('SUBSCRIPTION_QUOTA_LIMIT', '提醒额度已达上限')
  }

  const user = await findOne('users', { _id: openId })
  const serialized = serializeUser(user)
  return {
    subscribeQuota: serialized.subscribeQuota,
    subscribedAt: serialized.subscribedAt
  }
}

async function getFeed(openId, event) {
  const cursor = decodeCursor(event && event.cursor)
  const user = await ensureUser(openId)
  const idolId = typeof user.idolId === 'string' ? user.idolId : null

  if (!idolId) {
    return { posts: [], hasMore: false, nextCursor: null }
  }

  const sources = await loadSources(idolId, false)
  return queryFeedPage(idolId, cursor, sources)
}

const ACTIONS = {
  bootstrap,
  getHome,
  listIdols,
  setIdol,
  recordSubscription,
  getFeed
}

exports.main = async (event = {}) => {
  const action = event && event.action

  try {
    assert(typeof action === 'string' && Object.prototype.hasOwnProperty.call(ACTIONS, action), 'INVALID_ACTION', '不支持的操作')
    const openId = getOpenId()
    const data = await ACTIONS[action](openId, event)
    return ok(data)
  } catch (error) {
    const code = error instanceof AppError ? error.code : 'INTERNAL_ERROR'
    console.error('[user]', { action: typeof action === 'string' ? action : 'unknown', code })
    return fail(error)
  }
}

exports._helpers = {
  serializeUser,
  serializeIdol,
  serializePost,
  validateDocumentId,
  startOfShanghaiDay
}
