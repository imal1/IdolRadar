'use strict'

function timestamp(post) {
  const date = post && post.publishedAt instanceof Date
    ? post.publishedAt
    : new Date(post && post.publishedAt)
  return Number.isFinite(date.getTime()) ? date.getTime() : 0
}

function selectLatestPostsByIdol(posts) {
  const latest = new Map()

  for (const post of Array.isArray(posts) ? posts : []) {
    if (!post || typeof post.idolId !== 'string' || !post.idolId) continue
    const current = latest.get(post.idolId)
    if (
      !current ||
      timestamp(post) > timestamp(current) ||
      timestamp(post) === timestamp(current) && String(post._id || '') > String(current._id || '')
    ) {
      latest.set(post.idolId, post)
    }
  }

  return latest
}

module.exports = { selectLatestPostsByIdol }
