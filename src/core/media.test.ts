import assert from 'node:assert/strict'
import test from 'node:test'
import {buildChoices, type VideoInfo} from './media.js'

test('buildChoices adds video choices and mp3 choice', () => {
  const info: VideoInfo = {
    title: 'demo',
    formats: [
      {format_id: 'v1', height: 1080, vcodec: 'avc1', acodec: 'none', filesize: 1000, tbr: 1000, ext: 'mp4'},
      {format_id: 'a1', vcodec: 'none', acodec: 'mp4a', abr: 128, filesize: 100},
    ],
  }

  const choices = buildChoices(info)
  assert.ok(choices.some(choice => choice.kind === 'video'))
  assert.ok(choices.some(choice => choice.kind === 'audio' && choice.label.includes('mp3')))
})

test('buildChoices falls back when no specific heights are available', () => {
  const info: VideoInfo = {
    title: 'demo',
    formats: [],
  }

  const choices = buildChoices(info)
  assert.equal(choices[0]?.label, 'best available · mp4')
})
