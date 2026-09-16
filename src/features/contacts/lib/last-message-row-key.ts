import type { ChatRoom } from "@/entities/chat";

/**
 * What a chat list row shows of the room's last message beyond its timestamp, status, content
 * and sender: the reaction on it and the inputs `formatPreview` reads for calls, system events,
 * polls, transfers, call links and deletions. A last message can change in place — a reaction
 * added, a hangup that turned missed — while every one of those other fields stays the same.
 */
export function lastMessageRowKey(room: ChatRoom): string {
  const msg = room.lastMessage;
  const reaction = room.lastMessageReaction;
  return JSON.stringify([
    msg?.type,
    msg?.deleted,
    msg?.callInfo?.callType,
    msg?.callInfo?.missed,
    msg?.systemMeta?.template,
    msg?.systemMeta?.senderAddr,
    msg?.systemMeta?.targetAddr,
    msg?.systemMeta?.extra,
    msg?.pollInfo?.question,
    msg?.transferInfo?.amount,
    msg?.callLinkInfo?.label,
    reaction?.emoji,
    reaction?.senderAddress,
    reaction?.timestamp,
  ]);
}
