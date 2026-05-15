import axios from 'axios';

const api = axios.create({baseURL: 'http://localhost:8080'});
export const getRooms = () => api.get('/rooms');
export const getParticipants = () => api.get('/participants');
export const createRoom = (roomName: string) => api.put('/create-room', {roomName});
export const addParticipant = (participantName: string) => api.put('/add-participant', {participantName});
export const addParticipantToRoom = (roomName: string, participantName: string) => api.put('/add-participant-to-room', {
    roomName,
    participantName
});
export const sendMessage = (roomName: string, participantName: string, message: string) => api.put('/send-message', {
    roomName,
    participantName,
    message
});
