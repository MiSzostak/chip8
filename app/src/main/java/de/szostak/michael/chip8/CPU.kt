package de.szostak.michael.chip8

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*

object CPU {
    private val tag = javaClass.simpleName

    // 8 bit memory with 16 bit pc
    var memory = Array(4096, {0})
    var pc: Int = 0

    // 8 bit registers and 16 bit I register
    var V = Array(16, {0})
    var I: Int = 0

    // 16 bit stack with 8 bit sp
    var stack = Array(16, {0})
    var sp: Int = 0

    // 8 bit timer
    var soundTimer: Int = 0
    var delayTimer: Int = 0

    // keyboard

    // 64x32 display
    var display = Array(64, {IntArray(32)})
    var drawFlag = false

    // RNG
    val random: Random = Random()

    // the cpu cycle speed in Hz
    // TODO: make this optional
    val cycleSpeed = 1

    fun tick() {
        val startTime: Long = System.nanoTime()

        drawFlag = false
        decode(fetch())

        // TODO: decrement at correct speed
        if (delayTimer > 0) delayTimer--
        if (soundTimer > 0) soundTimer--

        val endTime: Long = System.nanoTime()
        val ticks: Long = (1000000 / cycleSpeed).toLong()

        val remainingCycleWaitTime = ticks - (endTime - startTime)

        if (remainingCycleWaitTime > 0) {
            Thread.sleep(remainingCycleWaitTime / 1000)
        }
    }

    fun fetch(): Int {
        return ((memory[pc] and 0xFF) shl 8) or (memory[pc +1] and 0xFF)
    }

    fun decode(opcode: Int): Int {
        // TODO: write method description
        // TODO: find useful return value
        val index = (opcode and 0xF000) shr 12
        val value = opcode and 0xFFF

        val x = (value and 0xF00) shr 8
        val y = (value and 0x0F0) shr 4
        val z = (value and 0x00F)

        when (index) {
            0 -> {
                when (value) {
                    0x0E0 -> {
                        // clear the display
                        display = Array(64, { IntArray(32) })
                    }
                    0x0EE -> {
                        // return from subroutine
                        sp--
                        pc = stack[sp]
                    }
                    else -> {
                        // error or call to RCA 1802 program
                        Log.e(tag, "Unimplemented opcode ${opcode.toString(16)}")
                    }
                }
            }
            1 -> {
                // jump to address 'value'
                pc = value
            }
            2 -> {
                // call subroutine at 'value'
                stack[sp] = pc
                sp++
                pc = value
            }
            3 -> {
                // skip next instruction if Vx == NN
                if (V[x] == (value and 0xFF)) pc += 2
            }
            4 -> {
                // skip next instruction if Vx != NN
                if (V[x] != (value and 0xFF)) pc += 2
            }
            5 -> {
                // skip next instruction if Vx == Vy
                if (V[x] == V[y]) pc += 2
            }
            6 -> {
                // set Vx to NN
                V[x] = value and 0xFF
            }
            7 -> {
                // add NN to Vx
                V[x] += value and 0xFF
            }
            8 -> {
                when (z) {
                    0 -> {
                        // set Vx to Vy
                        V[x] = V[y]
                    }
                    1 -> {
                        // set Vx to Vx OR Vy
                        V[x] = V[x] or V[y]
                    }
                    2 -> {
                        // set Vx to Vx AND Vy
                        V[x] = V[x] and V[y]
                    }
                    3 -> {
                        // set Vx to Vx XOR Vy
                        V[x] = V[x] xor V[y]
                    }
                    4 -> {
                        // add Vy to Vx
                        // emulated registers are 8 bit
                        // so the carry flag triggers after 255
                        val total = V[x] + V[y]
                        if (total > 255) {
                            V[x] = (total - 256) and 0xFF
                            V[0xF] = 1
                        } else {
                            V[x] = total
                            V[0xF] = 0
                        }
                    }
                    5 -> {
                        // subtract Vy from Vx
                        val total = V[x] - V[y]
                        if (total < 0) {
                            V[x] = total + 256
                            V[0xF] = 0
                        } else {
                            V[x] = total
                            V[0xF] = 1
                        }
                    }
                    6 -> {
                        // right-shift Vy by 1 and store result in Vx
                        // CF set to lsb of Vy before shift
                        V[0xF] = V[y] and 0x1
                        V[x] = (V[y] shr 1) and 0xFF
                    }
                    7 -> {
                        // subtract Vx from Vy
                        // store result in Vx
                        val total = V[y] - V[x]
                        if (total < 0) {
                            V[x] = total + 256
                            V[0xF] = 0
                        } else {
                            V[x] = total
                            V[0xF] = 1
                        }
                    }
                    0xE -> {
                        // left-shift Vy by 1 and store result in Vx
                        // CF set to msb of Vy before shift
                        V[0xF] = (V[y] and 0x80) shr 7
                        V[x] = (V[y] shl 1) and 0xFF
                    }
                    else -> {
                        Log.e(tag, "Unimplemented opcode ${opcode.toString(16)}")
                    }
                }
            }
            9 -> {
                // skip next instruction if Vx != Vy
                if (V[x] != V[y]) pc += 2
            }
            0xA -> {
                // set I to 'value'
                I = value
            }
            0xB -> {
                // jump to address at 'value' + V[0]
                pc = value + V[0]
            }
            0xC -> {
                // set Vx to RNG(0 - 255) AND NN
                V[x] = random.nextInt(256) and (value and 0xFF)
            }
            0xD -> {
                // draw sprite
                V[0xF] = 0

                for (yPoint in 0 until z) {
                    val pixelValue = memory[I + yPoint]
                    val yPos = (V[y] + yPoint) % 32
                    for (xPoint in 0 until 8) {
                        val xPos = (V[x] + xPoint) % 64
                        if ((pixelValue and (0x80 shr xPoint)) != 0) {
                            if (display[xPos][yPos] == 1) V[0xF] = 1
                            display[xPos][yPos] = display[xPos][yPos] xor 1
                        }
                    }
                }
                drawFlag = true
            }
            0xE -> {
                // TODO: Keyboard opcode 1
                Log.e(tag, "Unimplemented opcode ${opcode.toString(16)}")
            }
            0xF -> {
                when (value and 0xFF) {
                    0x7 -> {
                        // set Vx to delay timer
                        V[x] = delayTimer
                    }
                    0xA -> {
                        // wait for key press and store in Vx
                        // halts until next key event
                        // TODO: Keyboard opcode 2
                        Log.e(tag, "Unimplemented opcode ${opcode.toString(16)}")
                    }
                    0x15 -> {
                        // set delay timer to Vx
                        delayTimer = V[x]
                    }
                    0x18 -> {
                        // set sound timer to Vx
                        soundTimer = V[x]
                    }
                    0x1E -> {
                        // add Vx to I
                        I += V[x]
                    }
                    0x29 -> {
                        // set I to the location of the sprite for the character in Vx
                        I = V[x] * 5
                    }
                    0x33 -> {
                        // store binary coded decimal value of Vx in memory
                        // at position I, I + 1 and I + 2
                        val bcd = V[x]
                        memory[I] = bcd / 100
                        memory[I + 1] = (bcd % 100) / 10
                        memory[I + 2] = (bcd % 100) % 10
                    }
                    0x55 -> {
                        // dump registers 0 to x into memory starting at address I
                        for (i in 0 .. x) {
                            memory[I + i] = V[i]
                        }
                    }
                    0x65 -> {
                        // load registers 0 to x from memory starting at address I
                        for (i in 0 .. x) {
                            V[i] = memory[I + i]
                        }
                    }
                    else -> Log.e(tag, "Unimplemented opcode ${opcode.toString(16)}")
                }
            }
            else -> Log.e(tag, "Unimplemented opcode ${opcode.toString(16)}")
        }
        Log.d(tag, "Successfully executed opcode ${opcode.toString(16)} at position $pc")

        // TODO: see if this is the right place to increment
        pc += 2
        return opcode
    }

    fun reset() {
        memory = Array(4096, {0})
        pc = 512

        V = Array(16, {0})
        I = 0

        stack = Array(16, {0})
        sp = 0

        soundTimer = 0
        delayTimer = 0

        display = Array(64, {IntArray(32)})
        drawFlag = false

        // keyboard

        CPU.loadFile(BufferedReader(InputStreamReader(App.getAssetManager()
                .open("fontset"))), 0)

        CPU.loadFile(BufferedReader(InputStreamReader(App.getAssetManager()
                .open("test_rom"))), 512)
    }

    fun loadFile(reader: BufferedReader, from: Int) {
        // TODO: check behaviour for binary files
        // TODO: check if file isn't too big
        var position = from
        val values = reader.readLines()
        values.forEach {
            val value = it.split(" ")
            value.forEach {
                memory[position] = Integer.parseInt(it, 16) and 0xFF
                position++
            }
        }
        Log.d(tag, "Successfully loaded file into memory")
    }


    fun dumpMemory(from: Int, to: Int) {
        if (!isWithinMemory(from, to)) return
        Log.d(tag, "Dumping memory from address $from to $to")
        for (i in from until to) {
            Log.d(tag, "Pos $i: Dec ${memory[i]} Hex ${memory[i].toString(16)}")
        }
    }

    private fun isWithinMemory(from: Int, to: Int): Boolean {
        // TODO: see if kotlin offers an implementation for this
        // TODO: do unit test for this
        if (to <= from) return false
        if (from < 0 || from >= memory.size) return false
        if (to < 1 || to > memory.size) return false
        return true
    }
}