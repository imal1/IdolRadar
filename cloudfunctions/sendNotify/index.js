'use strict'

const crypto = require('node:crypto')
const cloud = require('wx-server-sdk')
const { buildSubscribeData } = require('./lib/message')

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()
const command = db.command
const USER_PAGE_SIZE = 100
const DEFAULT_SEND_CONCURRENCY = 8

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

function validateTemplateId() {
  const templateId = String(process.env.SUBSCRIBE_TEMPLATE_ID || '').trim()
  if (!templateId || templateId.length > 128 || /[\u0000-\u001f\u007f]/.test(templateId)) {
    return null
  }
  return templateId
}

function validatePostId(value) {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
}

function normalizeDate(value) {
  const date = value instanceof Date ? value : new Date(value)
  return Number.isFinite(date.getTime()) ? date : null
}

function validatePost(post) {
  return Boolean(
    post &&
    validatePostId(post._id) &&
    typeof post.idolId === 'string' && post.idolId.length > 0 && post.idolId.length <= 128 &&
    typeof post.title === 'string' && post.title.trim().length > 0 &&
    normalizeDate(post.publishedAt)
  )
}

async function findOne(collectionName, condition) {
  const result = await db.collection(collectionName).where(condition).limit(1).get()
  return result.data[0] || null
}

async function loadEligibleUsers(idolId, afterId) {
  const condition = {
    idolId,
    subscribeQuota: command.gt(0)
  }
  if (afterId) condition._id = command.gt(afterId)

  const result = await db.collection('users')
    .where(condition)
    .orderBy('_id', 'asc')
    .limit(USER_PAGE_SIZE)
    .get()
  return result.data
}

function updatedCount(result) {
  if (result && result.stats && Number.isInteger(result.stats.updated)) return result.stats.updated
  if (result && Number.isInteger(result.updated)) return result.updated
  return 0
}

async function reserveQuota(userId, idolId) {
  const result = await db.collection('users').where({
    _id: userId,
    idolId,
    subscribeQuota: command.gt(0)
  }).update({
    data: {
      subscribeQuota: command.inc(-1),
      updatedAt: db.serverDate()
    }
  })
  return updatedCount(result) === 1
}

async function restoreQuota(userId) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await db.collection('users').doc(userId).update({
        data: {
          subscribeQuota: command.inc(1),
          updatedAt: db.serverDate()
        }
      })
      return true
    } catch {
      // One immediate retry protects quota from a transient database error.
    }
  }
  return false
}

function anonymize(value) {
  return crypto.createHash('sha256').update(String(value), 'utf8').digest('hex').slice(0, 12)
}

function miniProgramState() {
  const value = String(process.env.MINIPROGRAM_STATE || 'formal').trim()
  return ['developer', 'trial', 'formal'].includes(value) ? value : 'formal'
}

async function sendToUser(user, idolId, message) {
  const userId = user && user._id
  if (typeof userId !== 'string' || !userId) return 'skipped'

  let reserved
  try {
    reserved = await reserveQuota(userId, idolId)
  } catch {
    console.error('[sendNotify]', { user: anonymize(userId), code: 'QUOTA_RESERVE_FAILED' })
    return 'failed'
  }
  if (!reserved) return 'skipped'

  try {
    const result = await cloud.openapi.subscribeMessage.send({
      touser: userId,
      templateId: message.templateId,
      page: message.page,
      data: message.data,
      miniprogramState: message.miniprogramState,
      lang: 'zh_CN'
    })
    if (result && Number.isFinite(Number(result.errCode)) && Number(result.errCode) !== 0) {
      throw new Error('OpenAPI rejected message')
    }
    return 'sent'
  } catch {
    const restored = await restoreQuota(userId)
    console.error('[sendNotify]', {
      user: anonymize(userId),
      code: restored ? 'SEND_FAILED' : 'SEND_FAILED_QUOTA_RESTORE_FAILED'
    })
    return 'failed'
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

exports.main = async (event = {}) => {
  if (isMiniProgramCall()) {
    return responseError('FORBIDDEN', '禁止从小程序端直接调用')
  }

  const templateId = validateTemplateId()
  if (!templateId) {
    console.error('[sendNotify]', { code: 'MISSING_TEMPLATE_ID' })
    return responseError('CONFIGURATION_ERROR', '订阅消息模板未配置')
  }

  if (!validatePostId(event && event.postId)) {
    return responseError('INVALID_INPUT', 'postId 无效')
  }

  try {
    const post = await findOne('posts', { _id: event.postId })
    if (!validatePost(post)) {
      return responseError('POST_NOT_FOUND', '动态不存在或数据不完整')
    }
    if (event.idolId !== undefined && event.idolId !== post.idolId) {
      return responseError('INVALID_INPUT', 'idolId 与动态不匹配')
    }

    const idol = await findOne('idols', { _id: post.idolId })
    if (!idol || typeof idol.name !== 'string' || !idol.name.trim()) {
      return responseError('IDOL_NOT_FOUND', '守护对象不存在')
    }

    const message = {
      templateId,
      page: `pages/radar/index?postId=${encodeURIComponent(post._id)}`,
      data: buildSubscribeData(idol.name, post.title, normalizeDate(post.publishedAt)),
      miniprogramState: miniProgramState()
    }
    const concurrency = positiveIntegerFromEnv('NOTIFY_CONCURRENCY', DEFAULT_SEND_CONCURRENCY, 20)
    const totals = { eligible: 0, sent: 0, failed: 0, skipped: 0 }
    let afterId = null

    while (true) {
      const users = await loadEligibleUsers(post.idolId, afterId)
      if (users.length === 0) break
      afterId = users[users.length - 1]._id
      totals.eligible += users.length

      const statuses = await mapWithConcurrency(
        users,
        concurrency,
        user => sendToUser(user, post.idolId, message)
      )
      for (const status of statuses) totals[status] += 1

      if (users.length < USER_PAGE_SIZE) break
    }

    return responseOk({
      postId: post._id,
      idolId: post.idolId,
      ...totals
    })
  } catch {
    console.error('[sendNotify]', { code: 'INTERNAL_ERROR' })
    return responseError('INTERNAL_ERROR', '推送任务执行失败')
  }
}

exports._helpers = {
  mapWithConcurrency,
  miniProgramState,
  normalizeDate,
  updatedCount,
  validatePost,
  validatePostId,
  validateTemplateId
}
