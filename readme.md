# CiCy Agent


    adb shell am clear-debug-app

    adb shell dumpsys meminfo com.cicy.agent | grep -E "PSS|TOTAL"
    adb shell dumpsys meminfo com.github.metacubex.clash.alpha | grep -E "PSS|TOTAL"
    
    
    ➜  cc-agent-adr git:(dev) ✗ adb shell dumpsys meminfo com.cc.agent.adr | grep -E "PSS|TOTAL"
    TOTAL   159182   134740     5256       67   319640    62498    55795     6702
    TOTAL PSS:   159182            TOTAL RSS:   319640       TOTAL SWAP PSS:       67
    ➜  cc-agent-adr git:(dev) ✗     adb shell dumpsys meminfo com.github.metacubex.clash.alpha | grep -E "PSS|TOTAL"
    
            TOTAL    52928    11612     2824    31791   153708    30731    25200     5530
               TOTAL PSS:    52928            TOTAL RSS:   153708       TOTAL SWAP PSS:    31791


    mCurrentFocus=Window{bb5eeed u0 com.github.metacubex.clash.alpha/com.github.kr328.clash.MainActivityAlias}
    mCurrentFocus=Window{e80cb9d u0 com.cc.agent.adr/com.cicy.agent.app.MainActivity}

    adb shell dumpsys window | grep "mCurrentFocus"
