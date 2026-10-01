package net.die.phoneapi.helper;

import net.die.phoneapi.helper.AxisRange;

parcelable TouchscreenInfo {
    int deviceId;
    int source;
    float maxX;
    float maxY;
    AxisRange pressure;
    AxisRange touchMajor;
    AxisRange touchMinor;
    AxisRange orientation;
    AxisRange size;
}
