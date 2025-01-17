/*
 * This is the source code of Telegram for Android v. 2.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2015.
 */

package org.telegramsecureplus.android;

import org.telegramsecureplus.messenger.R;
import org.telegramsecureplus.messenger.TLRPC;

public class UserObject {

    public static boolean isDeleted(TLRPC.User user) {
        return user == null || user instanceof TLRPC.TL_userDeleted_old2 || user instanceof TLRPC.TL_userEmpty || (user.flags & TLRPC.USER_FLAG_DELETED) != 0;
    }

    public static boolean isContact(TLRPC.User user) {
        return user instanceof TLRPC.TL_userContact_old2 || (user.flags & TLRPC.USER_FLAG_CONTACT) != 0 || (user.flags & TLRPC.USER_FLAG_MUTUAL_CONTACT) != 0;
    }

    public static boolean isUserSelf(TLRPC.User user) {
        return user instanceof TLRPC.TL_userSelf_old3 || (user.flags & TLRPC.USER_FLAG_SELF) != 0;
    }

    public static String getUserName(TLRPC.User user) {
        if (user == null || isDeleted(user)) {
            return LocaleController.getString("HiddenName", R.string.HiddenName);
        }
        return ContactsController.formatName(user.first_name, user.last_name);
    }

    public static String getFirstName(TLRPC.User user) {
        if (user == null || isDeleted(user)) {
            return "DELETED";
        }
        String name = user.first_name;
        if (name == null || name.length() == 0) {
            name = user.last_name;
        }
        return name != null && name.length() > 0 ? name : "DELETED";
    }
}
