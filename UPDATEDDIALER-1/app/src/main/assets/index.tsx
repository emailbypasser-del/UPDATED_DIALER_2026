import express from 'express'
import { createClient } from '@supabase/supabase-js'

const SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhzcGhqYXh0aWt2Zmh0c2hzeGx6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjQyMzI5NDMsImV4cCI6MjA3OTgwODk0M30.KDw4OSGD6VO-4JcgZ_JrdcDgYfNie2sxvqxEiDYQwVg"
const SUPABASE_URL = "https://xsphjaxtikvfhtshsxlz.supabase.co"
const app = express()
app.use(express.json({ limit: '12mb' }))
const supabase = createClient(
  SUPABASE_URL, SUPABASE_KEY
  )

app.post('/reports', async (req, res) => {
  try {
    const { agentName, branch, date, csv } = req.body
    if (!agentName || !branch || !date || !csv) {
      return res.status(400).json({ error: 'Missing required fields' })
    }
    const csvBuffer = Buffer.from(csv, 'base64')
    const { error } = await supabase.from('reports').insert([
      { agentname: agentName, branch, csv: csvBuffer, date }
    ])
    if (error) throw error
    res.status(200).json({ status: 'ok' })
  } catch (e) {
    console.error('[/reports]', e)
    res.status(500).json({ error: e.message })
  }
})

export default app