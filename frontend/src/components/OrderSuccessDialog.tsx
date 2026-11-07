'use client';

import React from 'react';
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
  Box,
  Paper,
  Chip,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import QrCode2Icon from '@mui/icons-material/QrCode2';
import ConfirmationNumberIcon from '@mui/icons-material/ConfirmationNumber';
import { OrderResponse } from '../types';

interface OrderSuccessDialogProps {
  order: OrderResponse | null;
  onClose: () => void;
}

export default function OrderSuccessDialog({ order, onClose }: OrderSuccessDialogProps) {
  if (!order) return null;

  return (
    <Dialog open={Boolean(order)} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ textAlign: 'center', pt: 3 }}>
        <CheckCircleIcon sx={{ fontSize: 56, color: 'secondary.main', mb: 1 }} />
        <Typography variant="h5" fontWeight={700}>
          Tickets Confirmed!
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Zero-Oversell Transaction Committed Successfully
        </Typography>
      </DialogTitle>
      <DialogContent>
        <Paper
          elevation={0}
          sx={{
            p: 2.5,
            bgcolor: 'rgba(255,255,255,0.03)',
            border: '1px dashed rgba(255,255,255,0.15)',
            borderRadius: 3,
            my: 1,
          }}
        >
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">
              ORDER ID
            </Typography>
            <Typography variant="caption" fontFamily="monospace">
              {order.orderId}
            </Typography>
          </Box>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">
              PAYMENT REFERENCE
            </Typography>
            <Chip
              label={order.paymentReference}
              size="small"
              color="secondary"
              variant="outlined"
            />
          </Box>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">
              TOTAL PAID
            </Typography>
            <Typography variant="body1" fontWeight={700} color="secondary.main">
              ${Number(order.totalAmount).toFixed(2)}
            </Typography>
          </Box>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
            <Typography variant="caption" color="text.secondary">
              RESERVED SEATS
            </Typography>
            <Typography variant="body2" fontWeight={600}>
              {order.seatIds.length} Seat(s)
            </Typography>
          </Box>

          <Box
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              p: 2,
              bgcolor: 'rgba(0,0,0,0.3)',
              borderRadius: 2,
              border: '1px solid rgba(255,255,255,0.08)',
            }}
          >
            <QrCode2Icon sx={{ fontSize: 96, color: '#f9fafb', mb: 1 }} />
            <Typography variant="caption" color="text.secondary" gutterBottom>
              Cryptographic Ticket HMAC-SHA256 Signature:
            </Typography>
            <Typography
              variant="caption"
              fontFamily="monospace"
              sx={{
                wordBreak: 'break-all',
                textAlign: 'center',
                color: 'primary.light',
                bgcolor: 'rgba(99,102,241,0.1)',
                px: 1,
                py: 0.5,
                borderRadius: 1,
              }}
            >
              {order.ticketQrSignature}
            </Typography>
          </Box>
        </Paper>
      </DialogContent>
      <DialogActions sx={{ p: 2.5, justifyContent: 'center' }}>
        <Button
          onClick={onClose}
          variant="contained"
          color="primary"
          fullWidth
          startIcon={<ConfirmationNumberIcon />}
        >
          View My Ticket
        </Button>
      </DialogActions>
    </Dialog>
  );
}
