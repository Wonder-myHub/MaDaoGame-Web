import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid({
  title: 'MaDaoGame 马刀游戏',
  description: '多人回合制网页对战游戏 - 技术文档',
  lang: 'zh-CN',
  base: '/MaDaoGame-Web/',
  lastUpdated: true,
  cleanUrls: true,

  head: [
    ['link', { rel: 'icon', href: '/favicon.ico' }]
  ],

  themeConfig: {
    logo: '⚔️',

    nav: [
      { text: '首页', link: '/' },
      { text: '入门指南', link: '/guide/getting-started' },
      { text: '架构设计', link: '/architecture/overview' },
      { text: 'API 参考', link: '/api/reference' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '入门指南',
          items: [
            { text: '快速开始', link: '/guide/getting-started' },
            { text: '游戏规则', link: '/guide/game-rules' },
          ]
        }
      ],
      '/architecture/': [
        {
          text: '架构设计',
          items: [
            { text: '架构概览', link: '/architecture/overview' },
            { text: '心跳机制', link: '/architecture/heartbeat' },
            { text: '并发模型', link: '/architecture/concurrency' },
          ]
        }
      ],
      '/api/': [
        {
          text: 'API 参考',
          items: [
            { text: 'API 速查表', link: '/api/reference' },
          ]
        }
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/Wonder-myHub/MaDaoGame-Web' }
    ],

    search: {
      provider: 'local'
    },

    footer: {
      message: '基于 VitePress 构建',
      copyright: `Copyright © ${new Date().getFullYear()} MaDaoGame`
    },

    outline: {
      level: [2, 3],
      label: '页面导航'
    },

    docFooter: {
      prev: '上一页',
      next: '下一页'
    },

    darkModeSwitchLabel: '主题',
    sidebarMenuLabel: '菜单',
    returnToTopLabel: '回到顶部',
    lastUpdated: {
      text: '最后更新于',
      formatOptions: {
        dateStyle: 'short',
        timeStyle: 'short'
      }
    }
  },

  markdown: {
    lineNumbers: true,
  }
})
