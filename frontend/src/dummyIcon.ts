import React from 'react'

const DummyIcon = () => null

export const __esModule = true

export default new Proxy({}, {
  get: function(target, prop) {
    if (prop === '__esModule') return true
    if (prop === 'default') return DummyIcon
    return DummyIcon
  }
})
