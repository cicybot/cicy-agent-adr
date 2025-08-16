package com.genymobile.scrcpy.control;

import android.view.KeyEvent;

import com.genymobile.scrcpy.device.Position;
import com.genymobile.scrcpy.util.Binary;
import com.genymobile.scrcpy.util.Ln;

public class ControlMessageReaderOut {
    
    private final String cmd;

    public ControlMessageReaderOut(String cmd) {
        this.cmd = cmd;
    }

    public int getType(){
        String[] parts = this.cmd.split("\\|");
        assert parts.length > 1;
        return Integer.parseInt(parts[0]);
    }

    public ControlMessage read() throws ControlProtocolException {
        int type = getType();
        switch (type) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                return parseInjectKeycode();
            case ControlMessage.TYPE_INJECT_TEXT:
                return parseInjectText();
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                return parseInjectTouchEvent();
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                return parseInjectScrollEvent();
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                return parseBackOrScreenOnEvent();
            case ControlMessage.TYPE_GET_CLIPBOARD:
                return parseGetClipboard();
            case ControlMessage.TYPE_SET_CLIPBOARD:
                return parseSetClipboard();
            case ControlMessage.TYPE_SET_DISPLAY_POWER:
                return parseSetDisplayPower();
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
            case ControlMessage.TYPE_COLLAPSE_PANELS:
            case ControlMessage.TYPE_ROTATE_DEVICE:
            case ControlMessage.TYPE_OPEN_HARD_KEYBOARD_SETTINGS:
            case ControlMessage.TYPE_RESET_VIDEO:
                return ControlMessage.createEmpty(type);
            case ControlMessage.TYPE_UHID_CREATE:
                return parseUhidCreate();
            case ControlMessage.TYPE_UHID_INPUT:
                return parseUhidInput();
            case ControlMessage.TYPE_UHID_DESTROY:
                return parseUhidDestroy();
            case ControlMessage.TYPE_START_APP:
                return parseStartApp();
            default:
                throw new ControlProtocolException("Unknown event type: " + type);
        }
    }

    private ControlMessage parseInjectKeycode()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length == 5;
        int action = Integer.parseInt(parts[1]);
        int keycode = Integer.parseInt(parts[2]);
        int repeat = Integer.parseInt(parts[3]);
        int metaState = Integer.parseInt(parts[4]);
        return ControlMessage.createInjectKeycode(action, keycode, repeat, metaState);
    }

    private ControlMessage parseInjectText()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length == 2;
        String text = parts[1];
        return ControlMessage.createInjectText(text);
    }

    private ControlMessage parseInjectTouchEvent()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length == 10;
        int action = Integer.parseInt(parts[1]);
        long pointerId = Long.parseLong(parts[2]);

        int x = Integer.parseInt(parts[3]);
        int y = Integer.parseInt(parts[4]);
        int screenWidth = Integer.parseInt(parts[5]);
        int screenHeight = Integer.parseInt(parts[6]);
        Position position = new Position(x, y, screenWidth, screenHeight);

//        short pressureSort = Short.parseShort(parts[7]);
        float pressure = Binary.u16FixedPointToFloat((short) 0xffff);

        int actionButton =Integer.parseInt(parts[8]);
        int buttons = Integer.parseInt(parts[9]);

        return ControlMessage.createInjectTouchEvent(action, pointerId, position, pressure, actionButton, buttons);
    }

    private ControlMessage parseInjectScrollEvent()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length == 8;
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int screenWidth = Integer.parseInt(parts[3]);
        int screenHeight = Integer.parseInt(parts[4]);

        Position position = new Position(x, y, screenWidth, screenHeight);

        short hScrollShort = Short.parseShort(parts[5]);
        short vScrollShort = Short.parseShort(parts[6]);

        // Binary.i16FixedPointToFloat() decodes values assuming the full range is [-1, 1], but the actual range is [-16, 16].
        float hScroll = Binary.i16FixedPointToFloat(hScrollShort) * 16;
        float vScroll = Binary.i16FixedPointToFloat(vScrollShort) * 16;
        int buttons = Integer.parseInt(parts[7]);

        return ControlMessage.createInjectScrollEvent(position, hScroll, vScroll, buttons);
    }

    private ControlMessage parseBackOrScreenOnEvent()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length == 2;
        int action = Integer.parseInt(parts[1]);
        return ControlMessage.createBackOrScreenOn(action);
    }

    private ControlMessage parseGetClipboard()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length == 2;
        int copyKey = Integer.parseInt(parts[1]);
        return ControlMessage.createGetClipboard(copyKey);
    }

    private ControlMessage parseSetClipboard()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length > 3;
        long sequence = Long.parseLong(parts[1]);
        boolean paste = Boolean.parseBoolean(parts[2]);
        String text  = this.cmd.replace(parts[0] + "|" + parts[1] + "|" +parts[2] + "|","");
        return ControlMessage.createSetClipboard(sequence, text, paste);
    }

    private ControlMessage parseSetDisplayPower()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length  == 2;
        long onInt = Integer.parseInt(parts[1]);
        boolean on = onInt == 1;
        return ControlMessage.createSetDisplayPower(on);
    }

    private ControlMessage parseUhidCreate()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length  == 6;

        int id = Short.parseShort(parts[1]);
        int vendorId = Short.parseShort(parts[2]);
        int productId =Short.parseShort(parts[3]);
        String name = parts[4];

//        String dataStr = parts[5];
        //todo
        byte[] data = new byte[2];
        return ControlMessage.createUhidCreate(id, vendorId, productId, name, data);
    }

    private ControlMessage parseUhidInput()  {

        String[] parts = this.cmd.split("\\|");
        assert parts.length  == 3;
        int id = Short.parseShort(parts[1]);

//        String dataStr = parts[2];
        //todo
        byte[] data = new byte[2];
//        byte[] data = parseByteArray(2);
        return ControlMessage.createUhidInput(id, data);
    }

    private ControlMessage parseUhidDestroy()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length  == 2;
        int id = Short.parseShort(parts[1]);
        return ControlMessage.createUhidDestroy(id);
    }

    private ControlMessage parseStartApp()  {
        String[] parts = this.cmd.split("\\|");
        assert parts.length  == 2;
        String name = parts[1];
        return ControlMessage.createStartApp(name);
    }
}
