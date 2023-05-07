package ar.pelotude.ohhsugoi.db

import java.time.ZoneId

data class UserData(val snowflakeId: ULong, val zone: ZoneId?)

interface UsersDatabase {
    suspend fun setUser(user: UserData)

    suspend fun getUser(snowflake: ULong): UserData?
}