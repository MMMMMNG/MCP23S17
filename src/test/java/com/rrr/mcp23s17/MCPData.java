package com.rrr.mcp23s17;

import com.pi4j.util.StringUtil;

import java.util.ArrayList;
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
    private static final byte WRITE_OPCODE = 0x40;
    private static final byte READ_OPCODE = 0x41;
    private boolean writing;
    private byte registerAddr;
    private byte response;
    private byte address;
    private byte writeData;

    private List<MCPData> previousData = new ArrayList<>();

    private MCPData() {
    }

    private MCPData(MCPData copy) {
        this.writing = copy.writing;
        this.registerAddr = copy.registerAddr;
        this.response = copy.response;
        this.address = copy.address;
        this.writeData = copy.writeData;
        this.previousData = copy.previousData;
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

    /**
     * which bit to set in the response byte
     *
     * @param bit the bit numbered from LSB to MSB
     * @return this instance to further configure or build
     */
    MCPData respSetBit(int bit) {
        this.response |= 1 << bit;
        return this;
    }

    MCPData next() {
        var copy = new MCPData(this);
        previousData.add(this);
        this.previousData = null;//remove circular reference, making sure this instance's next() throws
        return copy;
    }

    /**
     * "renders" this instance into the byte array
     *
     * @return the byte array according to this instances config
     */
    private byte[] getCurrData() {
        var bytes = new byte[3];
        bytes[0] = (byte) ((writing ? WRITE_OPCODE : READ_OPCODE) | (address << 1));
        bytes[1] = registerAddr;
        bytes[2] = writing ? writeData : response;
        return bytes;
    }

    /**
     * Takes all the MCPData objects and "renders" them out into a single byte array
     *
     * @return the byte array to be used for SPI testing
     */
    byte[] build() {
        previousData.add(this);
        int prevSize = previousData.size();
        var bytes = new byte[prevSize * 3];
        for (int i = 0; i < prevSize; i++) {
            var triple = previousData.get(i).getCurrData();
            bytes[3 * i] = triple[0];
            bytes[3 * i + 1] = triple[1];
            bytes[3 * i + 2] = triple[2];
        }
        return bytes;
    }

    /**
     * Takes all the MCPData objects, repeats them n times while allowing something to be changed
     * according to the index of the repetition.
     * Useful for testing repeated write operations to every single chip in an array
     *
     * @param amount  how many repetitions to perform
     * @param changer a function that allows change of the repeated objects.
     *                The function is supplied the current MCPData instance and the index of the repetition
     * @return this instance changed according to changer with param amount-1
     */
    public MCPData repeatPrevious(int amount, BiConsumer<MCPData, Integer> changer) {
        var newPrevious = new ArrayList<MCPData>();
        previousData.add(this);
        for (int i = 0; i < amount; i++) {
            for (var d : previousData) {
                changer.accept(d, i);
                newPrevious.add(new MCPData(d));
            }
        }
        var last = newPrevious.remove(newPrevious.size() - 1);
        last.previousData = newPrevious;
        return last;
    }

    /**
     * retrieves the element in previousData at index so it can be configured
     * <p>
     * sometimes it's easier to setup data with repeat method and then
     * change it after the fact.
     *
     * @param index the index into previousData
     * @return
     */
    public MCPData prevAtIndex(int index) {
        if (index == previousData.size()) return this;
        return previousData.get(index);
    }

    @Override
    public String toString() {
        return "MCPData {" + StringUtil.toHexString(getCurrData()) + "}";
    }

}
