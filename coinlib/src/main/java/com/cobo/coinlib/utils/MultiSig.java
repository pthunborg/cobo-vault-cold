/*
 *
 * Copyright (c) 2020 Cobo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * in the file COPYING.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.cobo.coinlib.utils;

public class MultiSig {
    public enum Account {
        P2SH("m/45'", "P2SH"),
        P2WSH_P2SH("m/48'/0'/0'/1'", "P2WSH-P2SH"),
        P2WSH("m/48'/0'/0'/2'", "P2WSH");

        private final String format;
        private String path;

        Account(String path, String format) {
            this.path = path;
            this.format = format;
        }

        public static Account ofPath(String path) {
            for (Account value : Account.values()) {
                if (value.path.equals(path)) {
                    return value;
                }
            }
            return P2WSH;
        }

        public static Account ofFormat(String format) {
            for (Account value : Account.values()) {
                if (value.format.equalsIgnoreCase(format)) {
                    return value;
                }
            }
            return P2WSH;
        }
        public static Account ofPrefix(String format) {
            for (Account value : Account.values()) {
                if (value.getXpubPrefix().equals(format)) {
                    return value;
                }
            }
            return P2WSH;
        }

        public String getPath() {
            return path;
        }

        public String getFormat() {
            return format;
        }

        public String getXpubPrefix() {
            switch (this) {
                case P2SH:
                    return "xpub";
                case P2WSH:
                    return "Zpub";
                case P2WSH_P2SH:
                    return "Ypub";
            }
            return "xpub";
        }
    }
}