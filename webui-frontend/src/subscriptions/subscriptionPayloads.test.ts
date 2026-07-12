import { describe, expect, it } from 'vitest'
import {
  buildSubscriptionAtAllPayload,
  buildSubscriptionCreatePayload,
  buildSubscriptionFilterPayload,
  buildSubscriptionRandomTemplatePayload,
  buildSubscriptionTemplatePayload,
  buildSubscriptionThemePayload,
  buildSubscriptionTargetPayload,
  buildSubscriptionUidPayload,
} from './subscriptionPayloads'

describe('subscription payload builders', () => {
  it('builds create payloads without authentication fields', () => {
    expect(buildSubscriptionCreatePayload({
      type: 'group',
      groupName: '测试分组',
      groupUid: '1001',
      groupTarget: '2001',
    })).toEqual({
      type: 'group',
      groupName: '测试分组',
      uid: '1001',
      targetGroup: '2001',
    })

  })

  it('builds nested editor payloads with business fields only', () => {
    expect(buildSubscriptionFilterPayload({
      key: 'filter-1',
      kind: 'regex',
      mode: 'black',
      content: '广告',
      targetGroups: ['onebot11:group:10001'],
    })).toEqual({
      key: 'filter-1',
      kind: 'regex',
      mode: 'black',
      content: '广告',
      targetGroups: ['onebot11:group:10001'],
    })

    expect(buildSubscriptionTemplatePayload({
      key: 'template-1',
      type: 'dynamic',
      name: '动态模板',
      content: '{{title}}',
      targetGroups: ['onebot11:group:10001'],
    })).toEqual({
      key: 'template-1',
      type: 'dynamic',
      name: '动态模板',
      content: '{{title}}',
      targetGroups: ['onebot11:group:10001'],
    })

    expect(buildSubscriptionAtAllPayload({
      type: 'Dynamic',
      targetGroups: ['10001', '10002'],
    })).toEqual({
      type: 'Dynamic',
      targetGroups: ['10001', '10002'],
    })

    expect(buildSubscriptionThemePayload('#33aaff', ['onebot11:group:10001'])).toEqual({
      color: '#33aaff',
      targetGroups: ['onebot11:group:10001'],
    })

    expect(buildSubscriptionRandomTemplatePayload(true)).toEqual({
      enabled: true,
    })

  })

  it('builds target and uid editor payloads with business fields only', () => {
    expect(buildSubscriptionTargetPayload({
      targetGroup: '10001',
    })).toEqual({
      targetGroup: '10001',
    })

    expect(buildSubscriptionUidPayload({
      uid: '12345',
    })).toEqual({
      uid: '12345',
    })
  })
})
