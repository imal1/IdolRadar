'use strict'

const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000

function startOfShanghaiDay(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value)
  if (!Number.isFinite(date.getTime())) {
    throw new Error('Invalid date')
  }

  const shifted = new Date(date.getTime() + SHANGHAI_OFFSET_MS)
  return new Date(
    Date.UTC(
      shifted.getUTCFullYear(),
      shifted.getUTCMonth(),
      shifted.getUTCDate()
    ) - SHANGHAI_OFFSET_MS
  )
}

module.exports = { startOfShanghaiDay }
