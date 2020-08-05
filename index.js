const Discord = require('discord.js')
const fs = require('fs')
const client = new Discord.Client()

if (!fs.existsSync('.env')) {
  console.error('.env file missing! Closing!')
  process.exit(1)
}

// Load in the .env file
require('dotenv').config()

function initialiseModule (name) {
  return require('./bot_modules/' + name + '/index.js').init(client)
}

client.on('ready', () => {
  console.log(`Logged in as ${client.user.tag}!`)
})

initialiseModule('config_manager')
initialiseModule('dump_analyse')

client.login(process.env.BOT_TOKEN)
So in the end, it turns out you can use a single forward slash and this will work for both Windows, OSX and Linux.