import { describe, expect, it } from 'vitest'
import {
  buildSubscriptionAtAllPayload,
  buildSubscriptionConfigDeletePayload,
  buildSubscriptionCreatePayload,
  buildSubscriptionDeletePayload,
  buildSubscriptionFilterPayload,
  buildSubscriptionRandomTemplatePayload,
  buildSubscriptionTemplatePayload,
  buildSubscriptionThemePayload,
  buildSubscriptionTargetPayload,
  buildSubscriptionUidPayload,
} from './subscriptionPayloads'

describe('subscription payload builders', () => {
  it('builds create and delete payloads with confirmation passwords', () => {
    expect(buildSubscriptionCreatePayload({
      type: 'group',
      groupName: '测试分组',
      groupUid: '1001',
      groupTarget: '2001',
      confirmationPassword: 'pw-create',
    })).toEqual({
      type: 'group',
      groupName: '测试分组',
      uid: '1001',
      targetGroup: '2001',
      confirmationPassword: 'pw-create',
    })

    expect(buildSubscriptionDeletePayload('item-1', 'pw-delete')).toEqual({
      itemId: 'item-1',
      confirmationPassword: 'pw-delete',
    })
  })

  it('builds nested editor payloads with confirmation passwords', () => {
    expect(buildSubscriptionFilterPayload({
      key: 'filter-1',
      kind: 'regex',
      mode: 'black',
      content: '广告',
      targetGroups: ['onebot11:group:10001'],
      confirmationPassword: 'pw-filter',
    })).toEqual({
      key: 'filter-1',
      kind: 'regex',
      mode: 'black',
      content: '广告',
      targetGroups: ['onebot11:group:10001'],
      confirmationPassword: 'pw-filter',
    })

    expect(buildSubscriptionTemplatePayload({
      key: 'template-1',
      type: 'dynamic',
      name: '动态模板',
      content: '{{title}}',
      targetGroups: ['onebot11:group:10001'],
      confirmationPassword: 'pw-template',
    })).toEqual({
      key: 'template-1',
      type: 'dynamic',
      name: '动态模板',
      content: '{{title}}',
      targetGroups: ['onebot11:group:10001'],
      confirmationPassword: 'pw-template',
    })

    expect(buildSubscriptionAtAllPayload({
      type: 'Dynamic',
      targetGroups: ['10001', '10002'],
      confirmationPassword: 'pw-atall',
    })).toEqual({
      type: 'Dynamic',
      targetGroups: ['10001', '10002'],
      confirmationPassword: 'pw-atall',
    })

    expect(buildSubscriptionThemePayload('#33aaff', 'pw-theme', ['onebot11:group:10001'])).toEqual({
      color: '#33aaff',
      targetGroups: ['onebot11:group:10001'],
      confirmationPassword: 'pw-theme',
    })

    expect(buildSubscriptionRandomTemplatePayload(true, 'pw-random')).toEqual({
      enabled: true,
      confirmationPassword: 'pw-random',
    })

    expect(buildSubscriptionConfigDeletePayload('pw-config-delete')).toEqual({
      confirmationPassword: 'pw-config-delete',
    })
  })

  it('builds target and uid editor payloads with confirmation passwords', () => {
    expect(buildSubscriptionTargetPayload({
      targetGroup: '10001',
      confirmationPassword: 'pw-target',
    })).toEqual({
      targetGroup: '10001',
      confirmationPassword: 'pw-target',
    })

    expect(buildSubscriptionUidPayload({
      uid: '12345',
      confirmationPassword: 'pw-uid',
    })).toEqual({
      uid: '12345',
      confirmationPassword: 'pw-uid',
    })
  })
})
