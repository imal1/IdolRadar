'use strict'

const { AppError } = require('./errors')

const CURSOR_VERSION = 1
const MAX_CURSOR_LENGTH = 512
const MAX_DOCUMENT_ID_LENGTH = 128

function normalizeDate(value) {
  const date = value instanceof Date ? value : new Date(value)
  if (!Number.isFinite(date.getTime())) {
    return null
  }
  return date
}

function encodeCursor(post) {
  const publishedAt = normalizeDate(post && post.publishedAt)
  const id = post && post._id

  if (!publishedAt || typeof id !== 'string' || id.length === 0) {
    throw new Error('Cannot create cursor from invalid post')
  }

  return Buffer.from(JSON.stringify({
    v: CURSOR_VERSION,
    p: publishedAt.toISOString(),
    i: id
  }), 'utf8').toString('base64url')
}

function decodeCursor(cursor) {
  if (cursor === undefined || cursor === null || cursor === '') {
    return null
  }

  if (
    typeof cursor !== 'string' ||
    cursor.length > MAX_CURSOR_LENGTH ||
    !/^[A-Za-z0-9_-]+$/.test(cursor)
  ) {
    throw new AppError('INVALID_CURSOR', '分页参数无效')
  }

  try {
    const decoded = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'))
    const publishedAt = normalizeDate(decoded && decoded.p)
    const id = decoded && decoded.i

    if (
      decoded.v !== CURSOR_VERSION ||
      !publishedAt ||
      typeof id !== 'string' ||
      id.length === 0 ||
      id.length > MAX_DOCUMENT_ID_LENGTH
    ) {
      throw new Error('Invalid cursor payload')
    }

    return { publishedAt, id }
  } catch (error) {
    if (error instanceof AppError) {
      throw error
    }
    throw new AppError('INVALID_CURSOR', '分页参数无效')
  }
}

module.exports = { encodeCursor, decodeCursor, normalizeDate }
