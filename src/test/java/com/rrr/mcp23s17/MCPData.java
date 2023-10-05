package com.rrr.mcp23s17;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Utility class to get mock data for verification.
 * <p>
 * This class can be used to get the data "like it should be", meaning
 * it can generate data to be passed as the "expected" argument to assertEquals()
 */
public class MCPData {
    private static final byte ADDR_IODIRA = 0x00;
    private static final byte ADDR_IODIRB = 0x01;
    private static final byte ADDR_IPOLA = 0x02;
    private static final byte ADDR_IPOLB = 0x03;
    private static final byte ADDR_GPINTENA = 0x04;
    private static final byte ADDR_GPINTENB = 0x05;
    private static final byte ADDR_DEFVALA = 0x06;
    private static final byte ADDR_DEFVALB = 0x07;
    private static final byte ADDR_INTCONA = 0x08;
    private static final byte ADDR_INTCONB = 0x09;
    private static final byte ADDR_IOCON = 0x0A;
    private static final byte ADDR_GPPUA = 0x0C;
    private static final byte ADDR_GPPUB = 0x0D;
    private static final byte ADDR_INTFA = 0x0E;
    private static final byte ADDR_INTFB = 0x0F;
    private static final byte ADDR_INTCAPA = 0x10;
    private static final byte ADDR_INTCAPB = 0x11;
    private static final byte ADDR_GPIOA = 0x12;
    private static final byte ADDR_GPIOB = 0x13;
    private static final byte ADDR_OLATA = 0x14;
    private static final byte ADDR_OLATB = 0x15;
    private static byte WRITE_OPCODE = 0x40;
    private static byte READ_OPCODE = 0x41;
    private boolean writing;
    private byte registerAddr;
    private byte response;
    private byte address;
    private byte writeData;

    private List<Byte> previousData = new ArrayList<>();

    private MCPData() {
    }

    static MCPData builder() {
        return new MCPData();
    }

    MCPData toIODIRA() {
        this.registerAddr = ADDR_IODIRA;
        return this;
    }

    MCPData toIODIRB() {
        this.registerAddr = ADDR_IODIRB;
        return this;
    }

    MCPData toIPOLA() {
        this.registerAddr = ADDR_IPOLA;
        return this;
    }

    MCPData toIPOLB() {
        this.registerAddr = ADDR_IPOLB;
        return this;
    }

    MCPData toGPINTENA() {
        this.registerAddr = ADDR_GPINTENA;
        return this;
    }

    MCPData toGPINTENB() {
        this.registerAddr = ADDR_GPINTENB;
        return this;
    }

    MCPData toDEFVALA() {
        this.registerAddr = ADDR_DEFVALA;
        return this;
    }

    MCPData toDEFVALB() {
        this.registerAddr = ADDR_DEFVALB;
        return this;
    }

    MCPData toINTCONA() {
        this.registerAddr = ADDR_INTCONA;
        return this;
    }

    MCPData toINTCONB() {
        this.registerAddr = ADDR_INTCONB;
        return this;
    }

    MCPData toIOCON() {
        this.registerAddr = ADDR_IOCON;
        return this;
    }

    MCPData toGPPUA() {
        this.registerAddr = ADDR_GPPUA;
        return this;
    }

    MCPData toGPPUB() {
        this.registerAddr = ADDR_GPPUB;
        return this;
    }

    MCPData toINTFA() {
        this.registerAddr = ADDR_INTFA;
        return this;
    }

    MCPData toINTFB() {
        this.registerAddr = ADDR_INTFB;
        return this;
    }

    MCPData toINTCAPA() {
        this.registerAddr = ADDR_INTCAPA;
        return this;
    }

    MCPData toINTCAPB() {
        this.registerAddr = ADDR_INTCAPB;
        return this;
    }

    MCPData toGPIOA() {
        this.registerAddr = ADDR_GPIOA;
        return this;
    }

    MCPData toGPIOB() {
        this.registerAddr = ADDR_GPIOB;
        return this;
    }

    MCPData toOLATA() {
        this.registerAddr = ADDR_OLATA;
        return this;
    }

    MCPData toOLATB() {
        this.registerAddr = ADDR_OLATB;
        return this;
    }

    MCPData read() {
        this.writing = false;
        return this;
    }

    MCPData write() {
        this.writing = true;
        return this;
    }

    MCPData address(int addr) {
        this.address = (byte) addr;
        return this;
    }

    MCPData writeData(int... states) {
        for (var state : states) {
            this.writeData = (byte) ((this.writeData << 1) | (state == 0 ? 0 : 1));
        }
        return this;
    }

    MCPData response(int... states) {
        for (var state : states) {
            this.response = (byte) ((this.response << 1) | (state == 0 ? 0 : 1));
        }
        return this;
    }

    MCPData next(int times, BiConsumer<MCPData, Integer> changer) {
        for (int i = 0; i < times; i++) {
            changer.accept(this, i);
            previousData.addAll(Arrays.asList(getCurrData()));
        }
        return this;
    }

    MCPData next() {
        return this.next(1, (d, i) -> {
        });
    }

    private Byte[] getCurrData() {
        var bytes = new Byte[3];
        bytes[0] = (byte) ((writing ? WRITE_OPCODE : READ_OPCODE) | address);
        bytes[1] = registerAddr;
        bytes[2] = writing ? writeData : response;
        return bytes;
    }

    byte[] build() {
        int prevSize = previousData.size();
        var bytes = new byte[prevSize + 3];
        for (int i = 0; i < prevSize; i++) {
            bytes[i] = previousData.get(i);
        }
        var currBs = getCurrData();
        for (int i = 0; i < 3; i++) {
            bytes[prevSize + i] = currBs[i];
        }
        return bytes;
    }

}
