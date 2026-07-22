'use strict'

// 开发用：往 posts 集合塞演示动态，固定 _id，重复执行幂等。
const cloud = require('wx-server-sdk')

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })

const db = cloud.database()

function hoursAgo(h) {
  return new Date(Date.now() - h * 3600 * 1000)
}

const POSTS = [
  { id: '01', idolId: 'idol_demo_lin_wan', channel: '微博', title: '今天路透被拍到啦，大家说妆造好看嘛', summary: '收工路上随手拍，晚上的风好舒服。明天有新歌预告，记得来蹲哦～', h: 1 },
  { id: '02', idolId: 'idol_demo_lin_wan', channel: 'ins', title: 'ins 更新了三张自拍', summary: 'caption: moon says hi 🌙 配图是排练室的镜子自拍。', h: 4 },
  { id: '03', idolId: 'idol_demo_lin_wan', channel: 'B站', title: '【林晚】新歌《晚风信号》练习室版公开', summary: '4K 直拍，副歌部分的 wave 教科书级别，评论区已经炸了。', h: 9 },
  { id: '04', idolId: 'idol_demo_lin_wan', channel: '行程', title: '周六 19:30 音乐节压轴场次官宣', summary: '滨海音乐节第二天压轴，预计表演 4 首歌，含新歌首唱。', h: 26 },
  { id: '05', idolId: 'idol_demo_lin_wan', channel: '微博', title: '转发了工作室的杂志预告', summary: '九月刊封面人物，预售链接周五开。配文：谢谢大家，会继续加油的。', h: 50 },
  { id: '06', idolId: 'idol_demo_lin_wan', channel: 'B站', title: '直播回放：和粉丝聊了一小时天', summary: '聊到了巡演城市投票、猫、还有最近在看的书。全程高能可爱。', h: 74 },
  { id: '07', idolId: 'idol_demo_su_nian', channel: '微博', title: '苏念工作室：新剧杀青小作文', summary: '一百二十天的拍摄结束啦，角色再见，我们后会有期。', h: 3 },
  { id: '08', idolId: 'idol_demo_su_nian', channel: '行程', title: '下周三品牌活动出席确认', summary: '时间 14:00，地点上海西岸，有红毯环节。', h: 30 }
]

exports.main = async () => {
  let inserted = 0
  for (const p of POSTS) {
    await db.collection('posts').doc('seed_demo_post_' + p.id).set({
      data: {
        idolId: p.idolId,
        sourceId: 'source_demo_' + (p.idolId === 'idol_demo_su_nian' ? 'su_nian' : 'lin_wan') + '_official',
        channel: p.channel,
        title: p.title,
        summary: p.summary,
        link: 'https://example.com/demo/' + p.id,
        publishedAt: hoursAgo(p.h),
        fetchedAt: new Date()
      }
    })
    inserted += 1
  }
  return { ok: true, data: { inserted } }
}
