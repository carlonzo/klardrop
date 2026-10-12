QT += core gui widgets qml quick quickcontrols2 network

CONFIG += c++17

isEmpty(KLARDROP_VERSION) {
    KLARDROP_VERSION = 1.0.0
}
DEFINES += KLARDROP_VERSION=\\\"$$KLARDROP_VERSION\\\"

TARGET = klardrop-qt
TEMPLATE = app

SOURCES += \
    main.cpp \
    clientbridge.cpp

HEADERS += \
    clientbridge.h

RESOURCES += \
    qml.qrc

QMAKE_CXXFLAGS += -Wall -Wextra
