# RestAdmin

A RESTful API for managing a Minecraft server. Supports Minecraft 1.21.1 with the [Fabric](https://fabricmc.net/) mod loader.
Currently very limited in functionality.

## Authentication

All endpoints require a valid `Authorization` header with the format:

```
Authorization: Bearer <token>
```

The token must be at least 12 characters long and can be set in `./config/restadmin.json`.

```json
{
  "port": 7070,
  "host": "0.0.0.0",
  "token": "your_token_here"
}
```

## Endpoints

### General

#### Get Connected Players

- **GET** `/players`
- **Description**: Retrieves the list of connected players.
- **Response**: JSON array of [Player](#player) objects.

### Whitelist

#### Get Whitelist

- **GET** `/whitelist`
- **Description**: Retrieves the list of whitelisted players.
- **Response**: JSON array of player names.

#### Check Whitelist Status

- **GET** `/whitelist/{username_or_uuid}`
- **Description**: Checks if a player (by username or UUID) is on the whitelist.
- **Response**: [Player](#player) object.

#### Add to Whitelist

- **POST** `/whitelist/{username_or_uuid}`
- **Description**: Adds a player (by username or UUID) to the whitelist.
- **Response**: [Player](#player) object.

#### Remove from Whitelist

- **DELETE** `/whitelist/{username_or_uuid}`
- **Description**: Removes a player (by username or UUID) from the whitelist.
- **Response**: [Player](#player) object.

## Types

### Player

```ts
type Player = {
  name: string;
  id: string;
};
```
