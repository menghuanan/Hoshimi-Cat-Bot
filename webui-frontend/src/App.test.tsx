import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

/**
 * 组件测试需要稳定的浏览器路径，先切到目标 path 再渲染 App。
 */
function renderAtPath(path: string) {
  window.history.pushState({}, '', path)
  return render(<App />)
}

describe('webui shell routing', () => {
  it('renders the dashboard shell with the core navigation pages', () => {
    renderAtPath('/')

    expect(screen.getByRole('heading', {name: '动态机器人 WebUI'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '首页'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '系统配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '订阅管理'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '日志'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '管理员菜单'})).toBeInTheDocument()
  })

  it('switches pages when the shell navigation is used', () => {
    renderAtPath('/')

    fireEvent.click(screen.getByRole('button', {name: '系统配置'}))

    expect(screen.getByText('写入设置')).toBeInTheDocument()
  })

  it('keeps a dense React layout for dashboard cards and account modal', () => {
    renderAtPath('/')

    expect(screen.getByText('运行概览')).toBeInTheDocument()
    expect(screen.getByText('配置入口')).toBeInTheDocument()
    expect(screen.getByText('日志窗口')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: '管理员菜单'}))
    fireEvent.click(screen.getByRole('button', {name: '修改密码'}))

    expect(screen.getByRole('dialog', {name: '修改密码'})).toBeInTheDocument()
    expect(screen.getByLabelText('当前密码')).toBeInTheDocument()
  })

  it('renders the login screen for the login path', () => {
    renderAtPath('/login')

    expect(screen.getByRole('heading', {name: '登录'})).toBeInTheDocument()
    expect(screen.getByLabelText('WebUI 密码')).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '管理员菜单'})).not.toBeInTheDocument()
  })
})
