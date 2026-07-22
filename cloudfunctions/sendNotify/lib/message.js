'use strict'

const DEFAULT_THING_LENGTH = 20

function truncateThing(value, maxLength = DEFAULT_THING_LENGTH) {
  if (!Number.isInteger(maxLength) || maxLength < 1) {
    throw new Error('Invalid thing length')
  }
  const normalized = String(value ?? '').replace(/\s+/g, ' ').trim()
  return [...normalized].slice(0, maxLength).join('')
}

function formatMessageTime(value) {
  const date = value instanceof Date ? value : new Date(value)
  if (!Number.isFinite(date.getTime())) {
    throw new Error('Invalid message time')
  }

  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day} ${values.hour}:${values.minute}`
}

function buildSubscribeData(idolName, title, publishedAt) {
  return {
    thing1: { value: truncateThing(idolName) || '爱豆' },
    thing2: { value: truncateThing(title) || '有新动态' },
    time3: { value: formatMessageTime(publishedAt) }
  }
}

module.exports = { buildSubscribeData, formatMessageTime, truncateThing }
