'use strict'

class AppError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'AppError'
    this.code = code
  }
}

function assert(condition, code, message) {
  if (!condition) {
    throw new AppError(code, message)
  }
}

function ok(data) {
  return { ok: true, data }
}

function fail(error) {
  if (error instanceof AppError) {
    return {
      ok: false,
      error: { code: error.code, message: error.message }
    }
  }

  return {
    ok: false,
    error: { code: 'INTERNAL_ERROR', message: '服务暂时不可用' }
  }
}

module.exports = { AppError, assert, ok, fail }
